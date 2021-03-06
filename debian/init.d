#!/bin/bash
# chkconfig: 2345 84 16
# description: MediaWiki Lucene Search daemon
# vim: autoindent

SERVICE_NAME="Lucene Search daemon"
BINDIR=/a/search/lucene-search
LOGDIR=/a/search/log

. /lib/lsb/init-functions
. /etc/default/rcS

export PATH=/usr/bin:/usr/local/sbin:$PATH
pid=/var/run/lsearchd.pid

case "$1" in
        start)  
                # Check if running
                if [ -s $pid ] && kill -0 $(cat /var/run/lsearchd.pid) >/dev/null 2>&1; then
                        log_progress_msg "Already running"
                        log_end_msg 0
                        exit 0
                fi
                log_daemon_msg "Starting $SERVICE_NAME"

                # Increase FD limit
                ulimit -n 65536
                # Run the daemon
                if start-stop-daemon --start --quiet --background --user lsearch --chuid lsearch --pidfile $pid --make-pidfile --exec /usr/bin/java -- -Xmx20000m -Dsun.rmi.transport.tcp.handshakeTimeout=10000 -Djava.rmi.server.codebase=file://$BINDIR/LuceneSearch.jar -Djava.rmi.server.hostname=$HOSTNAME -classpath $CLASSPATH:/usr/share/java/udp2log-log4j.jar:$BINDIR/LuceneSearch.jar org.wikimedia.lsearch.config.StartupManager
                then
                        rc=0
                        sleep 1
                        if ! kill -0 $(cat /var/run/lsearchd.pid) >/dev/null 2>&1; then
                            log_failure_msg "$SERVICE_NAME failed to start"
                            rc=1
                        fi
                fi

                if [ $rc -eq 0 ]; then
                        log_end_msg 0
                else
                        log_end_msg 1
                        rm -f /var/run/lsearchd.pid
                fi
                ;;
        stop)   
                log_daemon_msg "Stopping $SERVICE_NAME"
                start-stop-daemon --stop --quiet --oknodo --pidfile $pid --retry 10
                log_end_msg $?
                rm -f /var/run/lsearchd.pid
                echo
                ;;
        restart)
                $0 stop
                $0 start
                ;;
        status)
                if [ -s $pid ] && kill -0 $(cat /var/run/lsearchd.pid) >/dev/null 2>&1; then
                        echo "$SERVICE_NAME is running"
                else
                        echo "$SERVICE_NAME is not running"
                fi
                ;;
        *)
                echo "Usage: $0 {start|stop|status|restart}"
                exit 1
                ;;
esac
