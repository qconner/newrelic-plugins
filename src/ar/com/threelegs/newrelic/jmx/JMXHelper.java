package ar.com.threelegs.newrelic.jmx;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import ar.com.threelegs.newrelic.Metric;

import com.newrelic.metrics.publish.util.Logger;

public class JMXHelper {
	
	private static final Logger LOGGER = Logger.getLogger(JMXHelper.class);
	
	public static <T> T run(String host, String port, String username, String password, JMXTemplate<T> template) throws ConnectionException {
		JMXServiceURL address;
		JMXConnector connector = null;
		T value = null;

		try {
			address = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
			Map<String, String[]> env = new Hashtable<String, String[]>();
			if (username != null && password != null) {
			    String[] s = { username, password };
			    env.put(JMXConnector.CREDENTIALS, s);
			}
			try {
				connector = JMXConnectorFactory.connect(address, env);
			} catch (IOException ex) {
				throw new ConnectionException(host, ex);
			}
			MBeanServerConnection mbs = connector.getMBeanServerConnection();

			value = template.execute(mbs);
		} catch (ConnectionException e3) {
		    LOGGER.info("failed to connect to JMX on " + host + ":" + port);
		    // try falling back to localhost for JMX access if this IP is for this host
		    try {
			LOGGER.debug("check to see if failed connection is for the host where this NR plugin / agent is running");
			if (isLocalIP(InetAddress.getByName(host))) {
			    host = "localhost";
			    LOGGER.info("IP is local.  Trying fallback JMX connection to " + host + ":" + port);
			    address = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
			    Map<String, String[]> env = new Hashtable<String, String[]>();
			    if (username != null && password != null) {
				String[] s = { username, password };
				env.put(JMXConnector.CREDENTIALS, s);
			    }
			    try {
				connector = JMXConnectorFactory.connect(address, env);
			    } catch (IOException ex) {
				throw new ConnectionException(host, ex);
			    }
			    MBeanServerConnection mbs = connector.getMBeanServerConnection();
			    value = template.execute(mbs);
			}
			else {
			    throw e3;
			}
		    }
		    catch (ConnectionException ce) {
			LOGGER.warn("during JMX fallback, failed to connect to JMX on " + host + ":" + port);
			throw ce;
		    }
		    catch (Exception e2) {
			LOGGER.error(e2);
		    }
		} catch (Exception e1) {
			LOGGER.error(e1);
		} finally {
			close(connector);
		}

		return value;
	}

	public static Set<ObjectInstance> queryConnectionBy(MBeanServerConnection connection, ObjectName objectName) throws Exception {
		return connection.queryMBeans(objectName, null);
	}

	public static Set<ObjectInstance> queryConnectionBy(MBeanServerConnection connection, String domain, String name, String type, String scope)
			throws Exception {
		return queryConnectionBy(connection, getObjectName(domain, name, type, scope));
	}

	@SuppressWarnings("unchecked")
	public static <T> T getAttribute(MBeanServerConnection connection, ObjectName objectName, String attribute) throws Exception {
		return (T) connection.getAttribute(objectName, attribute);
	}

	public static <T> T queryAndGetAttribute(MBeanServerConnection connection, String domain, String name, String type, String scope, String attribute)
			throws Exception {
		return queryAndGetAttribute(connection, getObjectName(domain, name, type, scope), attribute);
	}

	public static <T> T queryAndGetAttribute(MBeanServerConnection connection, ObjectName objectName, String attribute) throws Exception {
		Set<ObjectInstance> instances = queryConnectionBy(connection, objectName);

		if (instances != null && instances.size() == 1) {
			return getAttribute(connection, objectName, attribute);
		} else {
			return null;
		}
	}
	
	public static List<Metric> queryAndGetAttributes(MBeanServerConnection connection, ObjectName objectName, List<String> attributes) throws Exception {
		List<Metric> returnList = new ArrayList<Metric>();
		Set<ObjectInstance> instances = queryConnectionBy(connection, objectName);
		if (instances != null && instances.size() > 0) {
			for (ObjectInstance thisInstance : instances) {
				for (String thisAttribute : attributes) {
					try {
						returnList.add(new Metric(thisInstance.getObjectName().toString(), thisAttribute, (Number)getAttribute(connection, thisInstance.getObjectName(), thisAttribute)));
					} catch (Exception e) {
						// Not a number, won't add metric, but won't crash the dang thing
						LOGGER.error(e, "failed object: " + thisInstance.getObjectName().toString(), "failed attribute: " + thisAttribute);
					}
				}
			}
		}
		if (!returnList.isEmpty()) {
			return returnList;
		} else {
			LOGGER.debug("No results found.");
			return null;
		}
	}

	public static ObjectName getObjectName(String domain, String name, String type, String scope) throws Exception {
		Hashtable<String, String> map = new Hashtable<String, String>();
		if (name != null)
			map.put("name", name);
		if (type != null)
			map.put("type", type);
		if (scope != null)
			map.put("scope", scope);
		return ObjectName.getInstance(domain, map);
	}

	public static ObjectName getObjectNameByKeys(String domain, String... values) throws Exception {
		return ObjectName.getInstance(domain, hashtableOf(values));
	}

	public static Hashtable<String, String> hashtableOf(String... values) {
		Hashtable<String, String> hashtable = new Hashtable<String, String>();

		for (String s : values) {
			String[] v = s.split("=");
			hashtable.put(v[0], v[1]);
		}

		return hashtable;
	}

	private static void close(JMXConnector connector) {
		if (connector != null) {
			try {
				connector.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static boolean isLocalIP(InetAddress addr) {
	    // true if localhost
	    if (addr.isLoopbackAddress())
		return true;

	    // also true if 127.x.x.x
	    if (addr.isAnyLocalAddress())
		return true;

	    // look at each NIC
	    try {
		Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
		while (nics.hasMoreElements()) {
			NetworkInterface nic = nics.nextElement();
			Enumeration<InetAddress> ips = nic.getInetAddresses();
			while (ips.hasMoreElements()) {
			    InetAddress ip = ips.nextElement();
			    LOGGER.debug("isLocalIP? for " + addr + "  nic: " + ip);

			    if (ip.equals(addr))
				return true;
			}
		}
	    }
	    catch (SocketException e) {
		return false;
	    }
	    return false;
	}
}
