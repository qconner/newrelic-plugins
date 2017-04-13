package ar.com.threelegs.newrelic;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.typesafe.config.Config;

public class Hostname {
    public static String hostname(Config config) {
        InetAddress addr = null;
        String hostname = "unknown";
        
        try {
	    addr = java.net.InetAddress.getLocalHost();
            hostname = addr.getHostName();
        }
        catch (UnknownHostException e) {
                // do nothing
        }

        try {
            String alt = config.getString("hostname");
            if (alt.length() > 0)
                hostname = alt;
        }
        catch (com.typesafe.config.ConfigException e) {
                // do nothing
        }
	
        return hostname;
    }
}
