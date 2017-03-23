package ar.com.threelegs.newrelic;

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

public class CassandraRing extends Agent {

	private static final Logger LOGGER = Logger.getLogger(CassandraRing.class);
	private String name;
	private Config config;

	public CassandraRing(Config config, String pluginName, String pluginVersion) {
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
		        String discoHost = config.getString("discovery_host");
                        LOGGER.debug("getting ring hosts from discovery_host " + discoHost);
			List<String> ringHosts = CassandraHelper.getRingHosts(discoHost, config.getString("jmx_port"), config.getString("username"), config.getString("password"));
			// TODO: figure out why C* returns an empty list after a few minutes
			if (ringHosts.size() < 1) {
			    ringHosts.add(discoHost);
			    LOGGER.warn("cassandra JMX returned an empty list of nodes.  using discovery host as a fallback");
			}

			LOGGER.debug("getting metrics for hosts [" + ringHosts + "]...");

			allMetrics.add(new Metric("Cassandra/global/totalHosts", "count", ringHosts.size()));
			int downCount = 0;

			for (final String host : ringHosts) {
				LOGGER.debug("getting metrics for host [" + host + "]...");

				try {
				    List<Metric> metrics = JMXHelper.run(host, config.getString("jmx_port"), config.getString("username"), config.getString("password"),
									 new JMXTemplate<List<Metric>>() {
						@Override
						public List<Metric> execute(MBeanServerConnection connection) throws Exception {

							ArrayList<Metric> metrics = new ArrayList<Metric>();

							// Latency
							Double rl = JMXHelper.queryAndGetAttribute(connection, "org.apache.cassandra.metrics", "Latency", "ClientRequest", "Read", "Mean");
							TimeUnit rlUnit = TimeUnit.valueOf(((String)JMXHelper.queryAndGetAttribute(connection, "org.apache.cassandra.metrics", "Latency", "ClientRequest", "Read", "DurationUnit")).toUpperCase());
							rl = toMillis(rl, rlUnit);

							Double wl = JMXHelper.queryAndGetAttribute(connection, "org.apache.cassandra.metrics", "Latency", "ClientRequest", "Write", "Mean");
							TimeUnit wlUnit = TimeUnit.valueOf(((String)JMXHelper.queryAndGetAttribute(connection, "org.apache.cassandra.metrics", "Latency", "ClientRequest", "Write", "DurationUnit")).toUpperCase());
							wl = toMillis(wl, wlUnit);

							metrics.add(new Metric("Cassandra/hosts/" + host + "/Latency/Reads", "millis", rl));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Latency/Writes", "millis", wl));
							metrics.add(new Metric("Cassandra/global/Latency/Reads", "millis", rl));
							metrics.add(new Metric("Cassandra/global/Latency/Writes", "millis", wl));

							// System
							Integer cpt = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Compaction", "name=PendingTasks"), "Value");
							Long mpt = JMXHelper.queryAndGetAttribute(connection,
												  JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics",
																"type=ThreadPools",
																"path=internal",
																"scope=MemtablePostFlush",
																"name=PendingTasks"), "Value");

							metrics.add(new Metric("Cassandra/hosts/" + host + "/Compaction/PendingTasks", "count", cpt));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/MemtableFlush/PendingTasks", "count", mpt));

							// Storage
							Long load = JMXHelper.queryAndGetAttribute(connection,
												     JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics",
																   "type=Storage",
																   "name=Load"), "Count");
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Storage/Data", "bytes", load));
							metrics.add(new Metric("Cassandra/global/Storage/Data", "bytes", load));

							Long commitLog = JMXHelper.queryAndGetAttribute(connection,
													JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics",
																      "type=CommitLog",
																      "name=TotalCommitLogSize"), "Value");
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Storage/CommitLog", "bytes", commitLog));
							metrics.add(new Metric("Cassandra/global/Storage/CommitLog", "bytes", commitLog));

							// Cache
							Double kchr = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=KeyCache", "name=HitRate"),
									"Value");
							Long kcs = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=KeyCache", "name=Size"),
									"Value");
							Integer kce = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=KeyCache", "name=Entries"),
									"Value");
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/KeyCache/HitRate", "rate", kchr));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/KeyCache/Size", "bytes", kcs));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/KeyCache/Entries", "count", kce));
							metrics.add(new Metric("Cassandra/global/Cache/KeyCache/HitRate", "rate", kchr));
							metrics.add(new Metric("Cassandra/global/Cache/KeyCache/Size", "bytes", kcs));
							metrics.add(new Metric("Cassandra/global/Cache/KeyCache/Entries", "count", kce));

							Double rchr = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=RowCache", "name=HitRate"),
									"Value");
							Long rcs = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=RowCache", "name=Size"),
									"Value");
							Integer rce = JMXHelper.queryAndGetAttribute(connection,
									JMXHelper.getObjectNameByKeys("org.apache.cassandra.metrics", "type=Cache", "scope=RowCache", "name=Entries"),
									"Value");
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/RowCache/HitRate", "rate", rchr));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/RowCache/Size", "bytes", rcs));
							metrics.add(new Metric("Cassandra/hosts/" + host + "/Cache/RowCache/Entries", "count", rce));
							metrics.add(new Metric("Cassandra/global/Cache/RowCache/HitRate", "rate", rchr));
							metrics.add(new Metric("Cassandra/global/Cache/RowCache/Size", "bytes", rcs));
							metrics.add(new Metric("Cassandra/global/Cache/RowCache/Entries", "count", rce));

							return metrics;
						}

						private Double toMillis(Double sourceValue, TimeUnit sourceUnit) {
							switch (sourceUnit) {
							case DAYS:
								return sourceValue * 86400000;
							case MICROSECONDS:
								return sourceValue * 0.001;
							case HOURS:
								return sourceValue * 3600000;
							case MILLISECONDS:
								return sourceValue;
							case MINUTES:
								return sourceValue * 60000;
							case NANOSECONDS:
								return sourceValue * 1.0e-6;
							case SECONDS:
								return sourceValue * 1000;
							default:
								return sourceValue;
							}
						}
					});

					if (metrics != null)
						allMetrics.addAll(metrics);
				} catch (ConnectionException e) {
					allMetrics.add(new Metric("Cassandra/downtime/hosts/" + e.getHost(), "value", 1));
					downCount++;
					allMetrics.add(new Metric("Cassandra/downtime/global", "count", downCount));
					e.printStackTrace();
				} catch (Exception e) {
					LOGGER.error(e);
				}
			}

		} catch (ConnectionException e) {
			allMetrics.add(new Metric("Cassandra/downtime/hosts/" + e.getHost(), "value", 1));
			// TODO: change to correct value (qty of failed connections) when we
			// make discoveryHosts a list.
			allMetrics.add(new Metric("Cassandra/downtime/global", "count", 1));
			LOGGER.error(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			LOGGER.debug("pushing " + allMetrics.size() + " metrics...");
			int dropped = 0;
			for (Metric m : allMetrics) {
				if (m.value != null && !m.value.toString().equals("NaN"))
					reportMetric(m.name, m.valueType, m.value);
				else {
				    LOGGER.debug("dropping null/NaN metric: " + m.name);
				    dropped++;
				}
			}
			LOGGER.debug("pushing metrics: done! dropped (null/NaN) metrics: " + dropped);
		}
	}
}
