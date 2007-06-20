package org.wikimedia.lsearch.benchmark;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

/** Benchmark terms from a dictionary of words (word : frequency) */
public class WordTerms implements Terms {
	Logger log = Logger.getLogger(WordTerms.class);
	/** load words from file, e.g. ./test-data/words-wikilucene.ngram.gz */
	public static ArrayList<String> loadWordFreq(String path) throws IOException {
		BufferedReader in;
		if(path.endsWith(".gz"))
			in = new BufferedReader(
					new InputStreamReader(
							new GZIPInputStream(
									new FileInputStream(path))));
		else 
			in = new BufferedReader(
					new InputStreamReader(
							new FileInputStream(path)));
		
		String line="";
		ArrayList<String> words = new ArrayList<String>();
		int freqSum = 0;
		int freq,count=0;
		while((line = in.readLine())!=null){
			try{
				String[] parts = line.split(" : ");
				if(parts.length > 1){				
					freq = Integer.parseInt(parts[1]);
					freqSum += freq;					
				}
				words.add(parts[0].trim());
			} catch(NumberFormatException e){
				words.add(line.trim());
			}
			count++;
		}
		//System.out.println("Loaded "+count+" words with frequency sum of "+freqSum);
		return words;
	}
	
	ArrayList<String> words;
	
	public WordTerms(String path){
		try {
			words = loadWordFreq(path);
		} catch (IOException e) {
			log.error("Cannot open dictionary of search terms in "+path);
			e.printStackTrace();
		}
	}
	
	public String next() {
		return words.get((int)(Math.random()*words.size()));
	}

}
