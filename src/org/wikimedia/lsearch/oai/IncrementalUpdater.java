package org.wikimedia.lsearch.oai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.wikimedia.lsearch.beans.Article;
import org.wikimedia.lsearch.config.Configuration;
import org.wikimedia.lsearch.config.GlobalConfiguration;
import org.wikimedia.lsearch.config.IndexId;
import org.wikimedia.lsearch.index.IndexUpdateRecord;
import org.wikimedia.lsearch.interoperability.RMIMessengerClient;
import org.wikimedia.lsearch.util.Localization;
import org.wikimedia.lsearch.util.UnicodeDecomposer;

/**
 * Handles incremental updates using OAI-PMH interface.
 * 
 * @author rainman
 *
 */
public class IncrementalUpdater {
	public enum ServerType { TIMESTAMP, SEQUENCE };

	static Logger log = Logger.getLogger(IncrementalUpdater.class);
	protected static int maxQueueSize = 500;
	protected static int bufferDocs = 50;

	static public class OAIAuthenticator extends Authenticator {
		protected String username,password;
		
		public OAIAuthenticator(){
			Configuration config = Configuration.open();
			username = config.getString("OAI","username");
			password = config.getString("OAI","password");
			
		}
		
		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			if(username == null || password == null){
				log.error("OAI authentication error. Username/password pair not specified in configuration file.");
				return null;
			}
			log.info("Authenticating ... ");
			return new PasswordAuthentication(username,password.toCharArray());
		}		
	}
	
	/** 
	 * Syntax:
	 * java IncrementalUpdater [-d] [-t timestamp] [-s sleep] [-f dblist] [-e dbname] [-n] [--no-ranks] dbname1 dbname2 ... 
	 * Options:
	 *   -d   - daemonize, otherwise runs only one round of updates to dbs
	 *   -s   - sleep time after one cycle (default: 30s)
	 *   -t   - default timestamp if status file is missing (default: 2001-01-01)
	 *   -f   - file to read databases from
	 *   -n   - wait for notification of flush after done updating one db (default: true)
	 *   -e   - exclude dbname from incremental updates (overrides -f)
	 *   --no-ranks - don't fetch ranks
	 * 
	 * @param args
	 */
	public static void main(String[] args){
		// only for debugging: allows use of a different log file from the main process
		for (int i =0; i < args.length; i++) {
			if (args[i].equals("-configfile")) {
				Configuration.setConfigFile(args[++i]);
			}
		}

		// config
		Configuration config = Configuration.open();
		GlobalConfiguration global = GlobalConfiguration.getInstance();

		ArrayList<String> dbnames = new ArrayList<String>();
		boolean daemon = false;
		long sleepTime = 30000; // 30s
		String timestamp = null;
		String excludeFile = null;
		boolean useLocal = false;
		
		String dblist = null;
		boolean notification = true;
		HashSet<String> excludeList = new HashSet<String>();
		HashSet<String> firstPass = new HashSet<String>(); // if dbname is here, then it's our update pass
		String defaultTimestamp = "2001-01-01";
		boolean errors = false;
		boolean requestSnapshot = false;
		String noOptimizationDBlistFile = null;
		HashSet<String> noOptimizationDBs = new HashSet<String>();


		// old servers do timestamp-based transfers but that is unreliable and
		// may cause lost updates; new ones use sequence numbers which are more
		// reliable
		//
		String seq_first = null;    // first record to fetch
		ServerType server_type = null;

		// args
		for(int i=0; i<args.length; i++){
			if(args[i].equals("-d"))
				daemon = true;
			else if(args[i].equals("-s"))
				sleepTime = Long.parseLong(args[++i])*1000;
			else if(args[i].equals("-t"))
				timestamp = args[++i];
			else if(args[i].equals("-dt"))
				defaultTimestamp = args[++i];
			else if(args[i].equals("-f"))
				dblist = args[++i];
			else if(args[i].equals("-l"))
				useLocal = true;
			else if(args[i].equals("-e"))
				excludeList.add(args[++i]);
			else if(args[i].equals("-ef"))
				excludeFile = args[++i];
			else if(args[i].equals("-n"))
				notification = true;
			else if(args[i].equals("-sn"))
				requestSnapshot = true;
			else if(args[i].equals("-nof"))
				noOptimizationDBlistFile = args[++i];
			else if(args[i].equals("-q"))
				seq_first = args[++i];
			else if(args[i].equals("--help"))
				break;
			else if(args[i].equals("-configfile"))
				++i;  // skip argument
			else if(args[i].startsWith("-")){
				System.out.println("Unrecognized switch "+args[i]);
				return;
			} else
				dbnames.add(args[i]);
		}		
		if ( null != timestamp && null != seq_first ) {
			System.out.println( "Cannot specify both timestamp and sequence number" ); return;
		}
		if(useLocal)
			dbnames.addAll(global.getMyIndexDBnames());
		dbnames.addAll(readDBList(dblist));
		excludeList.addAll(readDBList(excludeFile));
		if( noOptimizationDBlistFile != null)
			noOptimizationDBs.addAll(readDBList(noOptimizationDBlistFile));
		
		if(dbnames.size() == 0){
			System.out.println("Syntax: java IncrementalUpdater [-d] [-s sleep] [-t timestamp] [-e dbname] [-f dblist] [-n] [--no-ranks] dbname1 dbname2 ...");
			System.out.println("Options:");
			System.out.println("  -d   - daemonize, otherwise runs only one round of updates to dbs");
			System.out.println("  -s   - sleep time in seconds after one cycle (default: "+sleepTime/1000+"s)");
			System.out.println("  -t   - timestamp to start from");
			System.out.println("  -dt  - default timestamp (default: "+defaultTimestamp+")");
			System.out.println("  -f   - dblist file, one dbname per line");
			System.out.println("  -l   - use all local dbnames");
			System.out.println("  -n   - wait for notification of flush after done updating one db (default: "+notification+")");
			System.out.println("  -e   - exclude dbname from incremental updates (overrides -f)");
			System.out.println("  -ef  - exclude db names listed in dblist file");
			System.out.println("  -sn  - immediately make unoptimized snapshot as updates finish ");
			System.out.println("  -nof - use with -sn to specify a file with databases not to be optimized");
			System.out.println("  -q   - sequence number to start from");
			return;
		}
		// preload
		UnicodeDecomposer.getInstance();
		for(String dbname: dbnames){
			Localization.readLocalization(global.getLanguage(dbname));
		}
		Localization.loadInterwiki();
		OAIAuthenticator auth = new OAIAuthenticator();
		
		maxQueueSize = config.getInt("OAI","maxqueue",500);
		bufferDocs = config.getInt("OAI","bufferdocs",50);
		log.trace( String.format( "maxQueueSize = %d, bufferDocs = %d\n", maxQueueSize, bufferDocs ) );
		firstPass.addAll(dbnames);
		// update
		do{
			main_loop:
			for(String dbname : dbnames){
				try{
					if(excludeList.contains(dbname)) {
						log.trace( String.format( "%s in excludeList, skipped\n", dbname ) );
						continue;
					}
					IndexId iid = IndexId.get(dbname);
					OAIHarvester harvester = new OAIHarvester(iid,iid.getOAIRepository(),auth);
					OAIHarvester harvesterSingle = new OAIHarvester(iid,iid.getOAIRepository(),auth);
					Properties status = new Properties();
					// read timestamp from status file
					File statf = new File(iid.getStatusPath());
					try {
						if(statf.exists()){
							FileInputStream fileis = new FileInputStream(iid.getStatusPath());
							status.load(fileis);
							fileis.close();
						}
					} catch (IOException e) {
						log.warn("I/O error reading status file for "+iid+" at "+iid.getStatusPath()+" : "+e.getMessage(),e);
					}

					// fetch next batch of records based on sequence number (new scheme) or
					// timestamp (old scheme)
					//
					String from = null, seq_next = null;
					ArrayList<IndexUpdateRecord> records = null;

					if ( null == server_type ) {	// server type not yet known
						if ( firstPass.contains( dbname ) ) {
							if ( null != seq_first ) {
								server_type = ServerType.SEQUENCE;
								seq_next = seq_first;
							} else if ( null != timestamp ) {
								server_type = ServerType.TIMESTAMP;
								from = timestamp;
							}
						}
						if ( null == seq_next && null == from ) {
							seq_next = status.getProperty( "sequence" );
							if ( null != seq_next ) {
								server_type = ServerType.SEQUENCE;
							} else {
								// The first successful transfer will tell us the server type
								//server_type = ServerType.TIMESTAMP;
								from = status.getProperty( "timestamp", defaultTimestamp );
							}
						}
					} else if ( ServerType.TIMESTAMP == server_type ) {
						from = (firstPass.contains( dbname ) && null != timestamp)
							? timestamp : status.getProperty( "timestamp", defaultTimestamp );
					} else {    // sequence numbers
						seq_next = (firstPass.contains( dbname ) && null != seq_first)
							? seq_first : status.getProperty( "sequence" );
					}
					if ( ServerType.SEQUENCE == server_type ) {  // working with sequence numbers
						log.info( "Resuming update of "+ iid + ", seq = " + seq_next );
						records = harvester.getRecordsSeq( seq_next, bufferDocs );
					} else if ( null == server_type ) {  // test if server can do sequence numbers
						log.info( "Resuming update of " + iid + ", from = " + from + ", next = -1" );
						records = harvester.getRecords( from, bufferDocs, true );
					} else {	      // working with timestamps
						log.info( "Resuming update of " + iid + ", from = " + from );
						records = harvester.getRecords( from, bufferDocs );
					}

					if ( records.size() == 0 ) {
						log.trace( String.format( "No records\n" ) );
						continue;
					}
					if ( null == server_type ) {	// first successful transfer
						server_type = harvester.hasNext() ? ServerType.SEQUENCE : ServerType.TIMESTAMP;
						log.trace( String.format( "Server type = %s\n", server_type ) );
					}
					boolean hasMore = false;
					do{
						// send to indexer
						RMIMessengerClient messenger = new RMIMessengerClient(true);
						try {
							// send main
							printRecords(records);
							ensureNotOverloaded(messenger,iid);
							log.info(iid+": Sending "+records.size()+" records to indexer");
							HashSet<String> fetch = messenger.enqueueFrontend(records.toArray(new IndexUpdateRecord[] {}),iid.getIndexHost());
							if(fetch.size()>0){
								// fetch additional
								ArrayList<IndexUpdateRecord> additional = new ArrayList<IndexUpdateRecord>();
								for(String pageid : fetch){
									additional.addAll(harvesterSingle.getRecord(pageid));
								}
								// send additional
								printRecords(additional);
								ensureNotOverloaded(messenger,iid);
								log.info(iid+": Sending additional "+additional.size()+" records to indexer");
								messenger.enqueueFrontend(additional.toArray(new IndexUpdateRecord[] {}),iid.getIndexHost());
							}
							
						} catch (Exception e) {
							log.warn("Error sending index update records of "+iid+" to indexer at "+iid.getIndexHost(),e);
							continue main_loop;
						}
						// more results?
						hasMore = harvester.hasMore();
						if(hasMore){
							log.info("Fetching more records...");
							records = harvester.getMoreRecords(bufferDocs);
						}
					} while(hasMore);

					// see if we need to wait for notification
					log.trace( String.format( "notification = %s\n", notification ) );
					if(notification){
						RMIMessengerClient messenger = new RMIMessengerClient(true);
						String host = iid.getIndexHost();
						boolean req = messenger.requestFlushAndNotify(dbname,host);
						log.trace( String.format( "req = %s\n", req ) );
						if(req){
							log.info("Waiting for flush notification for "+dbname);
							Boolean succ = null;
							do{
								Thread.sleep(1500);
								succ = messenger.isSuccessfulFlush(dbname,host);
								if(succ != null){
									if(succ){
										log.info("Flush of "+dbname+" successful");
										break;
									}
									else{
										log.warn("Flush of "+dbname+" NOT successful. Not updating status file.");
										continue main_loop; // unsuccessful update, try again later
									}
								}
							} while(succ == null);
							if(requestSnapshot){
								boolean optimize = !noOptimizationDBs.contains(dbname);
								// snapshot the content and highlight indexes without optimizing them
								String p = dbname+"|"+dbname+".pa*|"+dbname+".ns*|"+dbname+".h*";
								messenger.requestSnapshotAndNotify(host, optimize, p, false);
								log.info("Waiting for snapshot notification for "+dbname);
								while( !messenger.snapshotFinished(host,optimize,p,false) ){
									try {
										Thread.sleep(1500);
									} catch (InterruptedException e) {
										log.warn("Interrupted", e);
									}
								}
								log.info("Snapshot of "+dbname+" successful");
							}
						} else 
							continue main_loop;
					}
					
					// write timestamp and, possibly, updated sequence number; timestamp not needed
					// in the new scheme but we save it since it may be useful for debugging
					//
					status.setProperty( "timestamp", harvester.getResponseDate() );
					if ( ServerType.SEQUENCE == server_type ) {
						status.setProperty( "sequence", harvester.getSequence() );
					}
					try {
						if(!statf.exists())
							statf.getParentFile().mkdirs();
						FileOutputStream fileos = new FileOutputStream(statf,false);
						status.store(fileos,"Last incremental update timestamp");
						fileos.close();
						log.trace( String.format( "stored timestamp/sequence to status file\n" ) );
					} catch (IOException e) {
						log.warn("I/O error writing status file for "+iid+" at "+iid.getStatusPath()+" : "+e.getMessage(),e);
					}
					firstPass.remove(dbname);
					log.info("Finished update of "+iid);
				} catch(Exception e){
					e.printStackTrace();
					log.warn("Retry later: error while processing update for "+dbname+" : "+e.getMessage(),e);
					errors = true;
				}
			}
			if(daemon){
				try { // in case we are on a server that has very infrequent updates sleep for a while
					log.info("Sleeping for "+sleepTime+" ms");
					Thread.sleep(sleepTime);
				} catch (InterruptedException e) { }
			}
		} while(daemon);
		
		if(errors)
			System.exit(1);	
	}
	
	private static Collection<String> readDBList(String dblist) {
		ArrayList<String> dbnames = new ArrayList<String>();
		if(dblist != null){			
			try {
				BufferedReader file = new BufferedReader(new FileReader(dblist));
				String line;
				while((line = file.readLine()) != null)
					dbnames.add(line.trim());
				file.close();
			} catch (FileNotFoundException e) {
				System.out.println("Error: File "+dblist+" does not exist");
				System.exit(1);
			} catch (IOException e) {
				System.out.println("Error: I/O error reading dblist file "+dblist);
				System.exit(1);
			}
		}
		return dbnames;
	}

	private static void printRecords(ArrayList<IndexUpdateRecord> records) {
		for(IndexUpdateRecord rec : records){
			Article ar = rec.getArticle();
			log.info("Sending "+ar);
		}
	}

	private static void ensureNotOverloaded(RMIMessengerClient messenger, IndexId iid) throws InterruptedException{
		// check if indexer is overloaded
		int queueSize = 0;
		do{
			queueSize = messenger.getIndexerQueueSize(iid.getIndexHost());
			if(queueSize >= maxQueueSize){
				log.info("Remote queue is "+queueSize+", sleeping for 5s");
				Thread.sleep(5000); // sleep five seconds then retry
			}
		} while(queueSize >= maxQueueSize);

	}
	
}
