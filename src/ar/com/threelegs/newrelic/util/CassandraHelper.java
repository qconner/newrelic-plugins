package ar.com.threelegs.newrelic.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServerConnection;

import ar.com.threelegs.newrelic.jmx.JMXHelper;
import ar.com.threelegs.newrelic.jmx.JMXTemplate;

import com.newrelic.metrics.publish.util.Logger;

public class CassandraHelper {
	private static final Logger LOGGER = Logger.getLogger(CassandraHelper.class);

	@SuppressWarnings("rawtypes")
	public static List<String> getRingHosts(String discoveryHost, String jmxPort, String user, String pass) throws Exception {

		return JMXHelper.run(discoveryHost, jmxPort, user, pass, new JMXTemplate<List<String>>() {
			@Override
			public List<String> execute(MBeanServerConnection connection) throws Exception {
				return JMXHelper.queryAndGetAttribute(connection, JMXHelper.getObjectNameByKeys("org.apache.cassandra.db", "type=StorageService"), "LiveNodes");

			}
		});

	}
}
