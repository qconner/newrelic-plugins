package ar.com.threelegs.newrelic;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
//import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;


import com.newrelic.metrics.publish.Agent;
import com.newrelic.metrics.publish.util.Logger;
import com.typesafe.config.Config;

import ar.com.threelegs.newrelic.Hostname;

public class LinuxNetworking extends Agent {

    private static final Logger LOGGER = Logger.getLogger(LinuxNetworking.class);
    private String name;
    private Config config;

    private long lastReXmitSegs = -1;
    private long lastOutSegs = -1;

    public LinuxNetworking(Config config, String pluginName, String pluginVersion) {
        super(pluginName, pluginVersion);
        this.name = config.getString("name");
        this.config = config;
    }

    @Override
    public String getComponentHumanLabel() {
        return name;
    }

    @Override
    public void pollCycle() {
        LOGGER.debug("starting poll cycle");
        List<Metric> allMetrics = new ArrayList<Metric>();

        try {
            LOGGER.debug("read and parse counters from /proc/net/snmp");
            // TODO:  /proc/net/netstat

            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/net/snmp")));
            String line;
            long outSegs = -1, reXmitSegs = -1;
            boolean headersFound = false;
            int segColWanted = -1, reXmitColWanted = -1;
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split(" ");
                LOGGER.debug("tokens[0]: " + tokens[0]);
                if (tokens[0].equals("Tcp:")) {
                    if (!headersFound) {
                        // expect column names
                        for (int i=1; i < tokens.length; i++) {
                            //LOGGER.debug("token: " + tokens[i]);
                            if (tokens[i].equals("OutSegs")) {
                                segColWanted = i;
                            }
                            else if (tokens[i].equals("RetransSegs")) {
                                reXmitColWanted = i;
                            }
                        }
                        if (segColWanted > 0 && reXmitColWanted > 0)
                            headersFound = true;
                    }
                    else {
                        // expect columns of values
                        outSegs = Long.parseLong(tokens[segColWanted]);
                        LOGGER.debug("outSegs: " + outSegs);

                        reXmitSegs = Long.parseLong(tokens[reXmitColWanted]);
                        LOGGER.debug("retransSegs: ", reXmitSegs);
                    }
                }
            }
            br.close();

            // compute rate of packet loss here
            // ILO doing in New Relic
            Double proxyPacketLoss;
            if (lastReXmitSegs == -1)
                proxyPacketLoss = 0.0;
            else {
                proxyPacketLoss = (double)(reXmitSegs - lastReXmitSegs) / (double)(outSegs - lastOutSegs);
                LOGGER.debug("lastReXmitSegs: " + lastReXmitSegs);
                LOGGER.debug("lastOutSegs: " + lastOutSegs);
                LOGGER.debug("proxyPacketLoss: " + proxyPacketLoss);
            }
            // remember last counts
            lastReXmitSegs = reXmitSegs;
            lastOutSegs = outSegs;

            // add to metrics list
            if (outSegs >= 0 && reXmitSegs >= 0) {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPOutSegments", "count", outSegs));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPRetransmitSegments", "count", reXmitSegs));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/ProxyPacketLossRateSinceBoot", "rate", (double)reXmitSegs / outSegs));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/ProxyPacketLossRate", "rate", proxyPacketLoss));
            }
            else
                LOGGER.warn("could not compute TCP segment retransmission rate");
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            LOGGER.debug("pushing " + allMetrics.size() + " metrics...");
            int dropped = 0;
            for (Metric m : allMetrics) {
                if (m.value != null && !m.value.toString().equals("NaN")) {
                    // plugin: Component
                    reportMetric(m.name, m.valueType, m.value);
                }
                else {
                    LOGGER.debug("dropping null/NaN metric: " + m.name);
                    dropped++;
                }
            }
            LOGGER.debug("pushing metrics: done! dropped (null/NaN) metrics: " + dropped);
        }
    }
}
