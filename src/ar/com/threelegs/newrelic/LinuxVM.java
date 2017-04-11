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
    private long last_pgscank = -1L;
    private long last_pgscand = -1L;
    private long last_pgpgin = -1L;
    private long last_pgpgout = -1L;
    private long last_pswpin = -1L;
    private long last_pswpout = -1L;

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
            long pgscan_kswapd_dma = -1L;
            long pgscan_kswapd_dma32 = -1L;
            long pgscan_kswapd_normal = -1L;
            long pgscan_kswapd_movable = -1L;
            //
            long pgscan_direct_dma = -1L;
            long pgscan_direct_dma32 = -1L;
            long pgscan_direct_normal = -1L;
            long pgscan_direct_movable = -1L;
            //
            long pgscan_direct_throttle = -1L;
            //
            long pgpgin_now = -1L;
            long pgpgout_now = -1L;
            long pswpin_now = -1L;
            long pswpout_now = -1L;
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
                            pgpgin_now = Long.parseLong(tokens[1]);
                            break;
                        case "pgpgout":
                            pgpgout_now = Long.parseLong(tokens[1]);
                            break;
                        case "pswpin":
                            pswpin_now = Long.parseLong(tokens[1]);
                            break;
                        case "pswpout":
                            pswpout_now = Long.parseLong(tokens[1]);
                            break;
                        default:
                            // ignore
                            break;
                    }
                }
            }
            br.close();

            // compute normal page scan daemon activity
            long pgscank_now = pgscan_kswapd_dma +  pgscan_kswapd_dma32 + pgscan_kswapd_normal + pgscan_kswapd_movable;
            long pgscank;
            if (last_pgscank == -1)
                pgscank = 0;
            else
                pgscank = pgscank_now - last_pgscank;
            last_pgscank = pgscank_now;

            // compute direct page scan daemon event counts
            long pgscand_now = pgscan_direct_dma + pgscan_direct_dma32 + pgscan_direct_normal + pgscan_direct_movable;
            long pgscand;
            if (last_pgscand == -1)
                pgscand = 0;
            else
                pgscand = pgscand_now - last_pgscand;
            last_pgscand = pgscand_now;
            
            // add to metrics list
            if (pgscank < 0 || pgscand < 0) {
                LOGGER.warn("could not determine pgscank and pgscand rates");
            }
            else {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscank", "count", pgscank));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscand", "count", pgscand));

                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_dma", "count", pgscan_kswapd_dma));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_dma32", "count", pgscan_kswapd_dma32));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_normal", "count", pgscan_kswapd_normal));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_kswapd_movable", "count", pgscan_kswapd_movable));

                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_dma", "count", pgscan_direct_dma));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_dma32", "count", pgscan_direct_dma32));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_normal", "count", pgscan_direct_normal));
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_movable", "count", pgscan_direct_movable));
                //
                //allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgscan_direct_throttle", "count", pgscan_direct_throttle));
            }


            // compute page in event count
            long pgpgin;
            if (last_pgpgin == -1)
                pgpgin = 0;
            else
                pgpgin = pgpgin_now - last_pgpgin;
            last_pgpgin = pgpgin_now;

            // compute page out event count
            long pgpgout;
            if (last_pgpgout == -1)
                pgpgout = 0;
            else
                pgpgout = pgpgout_now - last_pgpgout;
            last_pgpgout = pgpgout_now;

            // add to metrics list
            if (pgpgin < 0 || pgpgout < 0) {
                LOGGER.warn("could not determine pgpgin and pgpgout counts");
            }
            else {
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgpgin", "count", pgpgin));
                allMetrics.add(new Metric("hosts/" + Hostname.hostname(config) + "/LinuxVirtualMemory/pgpgout", "count", pgpgout));
            }

            // compute swap in event count
            long pswpin;
            if (last_pswpin == -1)
                pswpin = 0;
            else
                pswpin = pswpin_now - last_pswpin;
            last_pswpin = pswpin_now;

            // compute swap out event count
            long pswpout;
            if (last_pswpout == -1)
                pswpout = 0;
            else
                pswpout = pswpout_now - last_pswpout;
            last_pswpout = pswpout_now;

            // add to metrics list
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
