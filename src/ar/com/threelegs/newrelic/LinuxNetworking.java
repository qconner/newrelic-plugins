package ar.com.threelegs.newrelic;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.management.MBeanServerConnection;

import com.newrelic.metrics.publish.Agent;
import com.newrelic.metrics.publish.util.Logger;
import com.typesafe.config.Config;

import ar.com.threelegs.newrelic.Hostname;


public class LinuxNetworking extends Agent {

    private static final Logger LOGGER = Logger.getLogger(LinuxNetworking.class);
    private String name;
    private Config config;

    // prior iteration counter values
    private Map<String, Long> last = new TreeMap<String, Long>();

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

            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/net/snmp")));

            Set snmpMetrics = new TreeSet<String>();
            snmpMetrics.add("OutSegs");
            snmpMetrics.add("RetransSegs");

            Map<String, Long> metricValues = tcpMetrics(snmpMetrics, br, "Tcp:");
            br.close();
            Map<String, Long> deltaMetrics = computeDeltas(metricValues);

            long outSegs = -1, retransSegs = -1;
            if (deltaMetrics.keySet().contains("OutSegs")) {
                outSegs = deltaMetrics.get("OutSegs");
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPOutSegments", "count", outSegs));
            }
            if (deltaMetrics.keySet().contains("RetransSegs")) {
                retransSegs = deltaMetrics.get("RetransSegs");
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPRetransmitSegments", "count", retransSegs));
            }
            if (outSegs > 0) {
                double rate = (double)retransSegs / (double)outSegs;
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPRetransmitRate", "rate", rate));
            }


            LOGGER.debug("read and parse counters from /proc/net/netstat");
            br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/net/netstat")));

            Set netstatMetrics = new TreeSet<String>();
            netstatMetrics.add("TCPDSACKRecv");          // DSACK receipt
            netstatMetrics.add("TCPDSACKOfoRecv");       // out of order DSACK receipt
            netstatMetrics.add("TCPLossProbes");         // Tail Loss Probe sent
            netstatMetrics.add("TCPLossProbeRecovery");  // confirmed packet loss

            metricValues = tcpMetrics(netstatMetrics, br, "TcpExt:");
            br.close();
            deltaMetrics = computeDeltas(metricValues);

	    if (outSegs > 0 && deltaMetrics.keySet().contains("TCPDSACKRecv") && deltaMetrics.keySet().contains("TCPDSACKOfoRecv")) {
                long ds = deltaMetrics.get("TCPDSACKRecv");
		long ofo = deltaMetrics.get("TCPDSACKOfoRecv");
                double dsack_rate = (double)(ds + ofo) / (double)outSegs;
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPDSACKRate", "rate", dsack_rate));
            }

            Long tlps = deltaMetrics.get("TCPLossProbes");
            if (outSegs > 0 && deltaMetrics.keySet().contains("TCPLossProbes")) {
                double tlp_rate = (double)deltaMetrics.get("TCPLossProbes") / (double)outSegs;
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/TCPLossProbeRate", "rate", tlp_rate));
            }

            for (String metric : deltaMetrics.keySet()) {
                    allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxNetworking/" + metric, "count", deltaMetrics.get(metric)));
            }
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


    private Map<String, Long> tcpMetrics(Set<String> metrics, BufferedReader reader, String headerPrefix) {
        Map<Integer, String> metricColumns = new TreeMap<Integer, String>();
        Map<String, Long> metricValues = new TreeMap<String, Long>();

        String line;
        boolean headersFound = false;

        try {
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(" ");
                if (tokens[0].equals(headerPrefix)) {
                    if (!headersFound) {
                        // expect column names
                        for (int i=1; i < tokens.length; i++) {
                            // check for a desired metric
                            if (metrics.contains(tokens[i])) {
                                // save column we found it in
                                metricColumns.put(i, tokens[i]);
                            }
                        }
                        // check for full set of metrics
                        headersFound = true;
                        for (String metric : metrics) {
                            if (metricColumns.containsValue(metric) == false) {
                                headersFound = false;
                                break;
                            }
                        }
                    }
                    else {
                        // expect columns of values
                        for (int i : metricColumns.keySet()) {
                            String name = metricColumns.get(i);
                            metricValues.put(name, Long.parseLong(tokens[i]));
                        }
                    }
                }
            }
        }
        catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        return metricValues;
    }


    private Map<String, Long> computeDeltas(Map<String, Long> metrics) {
        Map<String, Long> deltas = new TreeMap<String, Long>();
        for (String metric : metrics.keySet()) {
            Long last = this.last.get(metric);
            long current = metrics.get(metric);
            if (last != null)
                deltas.put(metric, current - last);
            this.last.put(metric, current);
        }
        return deltas;
    }
}
