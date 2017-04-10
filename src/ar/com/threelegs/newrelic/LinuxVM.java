package ar.com.threelegs.newrelic;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;

import ar.com.threelegs.newrelic.jmx.ConnectionException;
import ar.com.threelegs.newrelic.jmx.JMXHelper;
import ar.com.threelegs.newrelic.jmx.JMXTemplate;
import ar.com.threelegs.newrelic.util.CassandraHelper;

import com.newrelic.metrics.publish.Agent;
import com.newrelic.metrics.publish.util.Logger;
import com.typesafe.config.Config;

public class LinuxVM extends Agent {

    private static final Logger LOGGER = Logger.getLogger(LinuxVM.class);
    private String name;
    private Config config;

    public LinuxVM(Config config, String pluginName, String pluginVersion) {
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
            LOGGER.debug("read and parse counters from /proc/vmstat");

            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/vmstat")));
            String line;

	    //
	    long pgscan_kswapd_dma = -1;
	    long pgscan_kswapd_dma32 = -1;
	    long pgscan_kswapd_normal = -1;
	    long pgscan_kswapd_movable = -1;
	    //
	    long pgscan_direct_dma = -1;
	    long pgscan_direct_dma32 = -1;
	    long pgscan_direct_normal = -1;
	    long pgscan_direct_movable = -1;
	    //
	    long pgscan_direct_throttle = -1;
	    //
	    long pgpgin = -1;
	    long pgpgout = -1;
	    long pswpin = -1;
	    long pswpout = -1;
	    //
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split(" ");
                //LOGGER.debug("tokens[0]: " + tokens[0]);
                //LOGGER.debug("tokens[1]: " + tokens[1]);
		if (tokens.length == 2) {
                    switch (tokens[0]) {
			case "pgscan_kswapd_dma":
                            pgscan_kswapd_dma = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_kswapd_dma32":
                            pgscan_kswapd_dma32 = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_kswapd_normal":
                            pgscan_kswapd_normal = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_kswapd_movable":
                            pgscan_kswapd_movable = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_direct_dma":
                            pgscan_direct_dma = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_direct_dma32":
                            pgscan_direct_dma32 = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_direct_normal":
                            pgscan_direct_normal = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_direct_movable":
                            pgscan_direct_movable = Long.parseLong(tokens[1]);
			    break;
			case "pgscan_direct_throttle":
                            pgscan_direct_throttle = Long.parseLong(tokens[1]);
			    break;
			case "pgpgin":
                            pgpgin = Long.parseLong(tokens[1]);
			    break;
			case "pgpgout":
                            pgpgout = Long.parseLong(tokens[1]);
			    break;
			case "pswpin":
                            pswpin = Long.parseLong(tokens[1]);
			    break;
			case "pswpout":
                            pswpout = Long.parseLong(tokens[1]);
			    break;
                        default:
                            // ignore
			    break;
		    }
                }
            }
            br.close();

	    // aggregate normal and direct page scan daemon activity
	    long pgscank = pgscan_kswapd_dma +  pgscan_kswapd_dma32 + pgscan_kswapd_normal + pgscan_kswapd_movable;
	    long pgscand = pgscan_direct_dma + pgscan_direct_dma32 + pgscan_direct_normal + pgscan_direct_movable;

	    // add to metrics list
            if (pgscank < 0 || pgscand < 0) {
                LOGGER.warn("could not determine pgscank and pgscand rates");
	    }
	    else {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscank", "rate", pgscank));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscand", "rate", pgscand));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscand_throttle", "rate", pgscan_direct_throttle));

		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_dma", "rate", pgscan_kswapd_dma));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_dma32", "rate", pgscan_kswapd_dma32));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_normal", "rate", pgscan_kswapd_normal));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_movable", "rate", pgscan_kswapd_movable));

		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_dma", "rate", pgscan_direct_dma));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_dma32", "rate", pgscan_direct_dma32));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_normal", "rate", pgscan_direct_normal));
		allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_movable", "rate", pgscan_direct_movable));
	    }
	    

	    if (pgpgin < 0 || pgpgout < 0) {
                LOGGER.warn("could not determine pgpgin and pgpgout counts");
	    }
	    else {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgpgin", "count", pgpgin));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgpgout", "count", pgpgout));
	    }

	    if (pswpin < 0 || pswpout < 0) {
                LOGGER.warn("could not determine pswpin and pswpout counts");
	    }
	    else {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pswpin", "count", pswpin));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pswpout", "count", pswpout));
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
}
