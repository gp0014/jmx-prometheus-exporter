package com.kucoin.jmx.prometheus.exporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularType;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.naming.Context;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;


class JmxScraper {
    private static final Logger logger = LoggerFactory.getLogger(JmxScraper.class.getName());


    public interface MBeanReceiver {
        void recordBean(
                String jmxURL,
                String domain,
                LinkedHashMap<String, String> beanProperties,
                LinkedList<String> attrKeys,
                String attrName,
                String attrType,
                String attrDescription,
                Object value);
    }

    private final MBeanReceiver receiver;
    private final String jmxUrl;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final List<ObjectName> whitelistObjectNames, blacklistObjectNames;
    private final JmxMBeanPropertyCache jmxMBeanPropertyCache;
    private final OptionalValueExtractor optionalValueExtractor = new OptionalValueExtractor();

    public JmxScraper(String jmxUrl, String username, String password, boolean ssl,
                      List<ObjectName> whitelistObjectNames, List<ObjectName> blacklistObjectNames,
                      MBeanReceiver receiver, JmxMBeanPropertyCache jmxMBeanPropertyCache) {
        this.jmxUrl = jmxUrl;
        this.receiver = receiver;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
        this.whitelistObjectNames = whitelistObjectNames;
        this.blacklistObjectNames = blacklistObjectNames;
        this.jmxMBeanPropertyCache = jmxMBeanPropertyCache;
    }

    /**
     * Get a list of mbeans on host_port and scrape their values.
     * <p>
     * Values are passed to the receiver in a single thread.
     */
    public void doScrape() throws Exception {
        MBeanServerConnection beanConn;
        JMXConnector jmxc = null;
        if (jmxUrl.isEmpty()) {
            beanConn = ManagementFactory.getPlatformMBeanServer();
        } else {
            Map<String, Object> environment = new HashMap<String, Object>();
            if (username != null && username.length() != 0 && password != null && password.length() != 0) {
                String[] credent = new String[]{username, password};
                environment.put(JMXConnector.CREDENTIALS, credent);
            }
            if (ssl) {
                environment.put(Context.SECURITY_PROTOCOL, "ssl");
                SslRMIClientSocketFactory clientSocketFactory = new SslRMIClientSocketFactory();
                environment.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, clientSocketFactory);
                environment.put("com.sun.jndi.rmi.factory.socket", clientSocketFactory);
            }

            jmxc = JMXConnectorFactory.connect(new JMXServiceURL(jmxUrl), environment);
            beanConn = jmxc.getMBeanServerConnection();
        }
        try {
            // Query MBean names, see #89 for reasons queryMBeans() is used instead of queryNames()
            Set<ObjectName> mBeanNames = new HashSet<ObjectName>();
            for (ObjectName name : whitelistObjectNames) {
                for (ObjectInstance instance : beanConn.queryMBeans(name, null)) {
                    mBeanNames.add(instance.getObjectName());
                }
            }

            for (ObjectName name : blacklistObjectNames) {
                for (ObjectInstance instance : beanConn.queryMBeans(name, null)) {
                    mBeanNames.remove(instance.getObjectName());
                }
            }

            // Now that we have *only* the whitelisted mBeans, remove any old ones from the cache:
            jmxMBeanPropertyCache.onlyKeepMBeans(mBeanNames);

            long start = System.nanoTime();
            for (ObjectName objectName : mBeanNames) {
                scrapeBean(beanConn, objectName);
            }
            logger.info("TIME: {} ns for scrape {}", System.nanoTime() - start, jmxUrl);
        } finally {
            if (jmxc != null) {
                jmxc.close();
            }
        }
    }

    private void scrapeBean(MBeanServerConnection beanConn, ObjectName mbeanName) {
        MBeanInfo info;
        try {
            info = beanConn.getMBeanInfo(mbeanName);
        } catch (IOException e) {
            logScrape(mbeanName.toString(), "getMBeanInfo Fail: " + e);
            return;
        } catch (JMException e) {
            logScrape(mbeanName.toString(), "getMBeanInfo Fail: " + e);
            return;
        }
        MBeanAttributeInfo[] attrInfos = info.getAttributes();

        Map<String, MBeanAttributeInfo> name2AttrInfo = new LinkedHashMap<String, MBeanAttributeInfo>();
        for (int idx = 0; idx < attrInfos.length; ++idx) {
            MBeanAttributeInfo attr = attrInfos[idx];
            if (!attr.isReadable()) {
                logScrape(mbeanName, attr, "not readable");
                continue;
            }
            name2AttrInfo.put(attr.getName(), attr);
        }
        final AttributeList attributes;
        try {
            attributes = beanConn.getAttributes(mbeanName, name2AttrInfo.keySet().toArray(new String[0]));
            if (attributes == null) {
                logScrape(mbeanName.toString(), "getAttributes Fail: attributes are null");
                return;
            }
        } catch (Exception e) {
            logScrape(mbeanName, name2AttrInfo.keySet(), "Fail: " + e);
            return;
        }
        for (Object attributeObj : attributes.asList()) {
            if (attributeObj instanceof Attribute) {
                Attribute attribute = (Attribute) (attributeObj);
                MBeanAttributeInfo attr = name2AttrInfo.get(attribute.getName());
                logScrape(mbeanName, attr, "process");
                processBeanValue(
                        mbeanName.getDomain(),
                        jmxMBeanPropertyCache.getKeyPropertyList(mbeanName),
                        new LinkedList<String>(),
                        attr.getName(),
                        attr.getType(),
                        attr.getDescription(),
                        attribute.getValue()
                );
            }
        }
    }


    /**
     * Recursive function for exporting the values of an mBean.
     * JMX is a very open technology, without any prescribed way of declaring mBeans
     * so this function tries to do a best-effort pass of getting the values/names
     * out in a way it can be processed elsewhere easily.
     */
    private void processBeanValue(
            String domain,
            LinkedHashMap<String, String> beanProperties,
            LinkedList<String> attrKeys,
            String attrName,
            String attrType,
            String attrDescription,
            Object value) {
        if (value == null) {
            logScrape(domain + beanProperties + attrName, "null");
        } else if (value instanceof Number || value instanceof String || value instanceof Boolean || value instanceof java.util.Date) {
            if (value instanceof java.util.Date) {
                attrType = "java.lang.Double";
                value = ((java.util.Date) value).getTime() / 1000.0;
            }
            logScrape(domain + beanProperties + attrName, value.toString());
            this.receiver.recordBean(
                    jmxUrl,
                    domain,
                    beanProperties,
                    attrKeys,
                    attrName,
                    attrType,
                    attrDescription,
                    value);
        } else if (value instanceof CompositeData) {
            logScrape(domain + beanProperties + attrName, "compositedata");
            CompositeData composite = (CompositeData) value;
            CompositeType type = composite.getCompositeType();
            attrKeys = new LinkedList<String>(attrKeys);
            attrKeys.add(attrName);
            for (String key : type.keySet()) {
                String typ = type.getType(key).getTypeName();
                Object valu = composite.get(key);
                processBeanValue(
                        domain,
                        beanProperties,
                        attrKeys,
                        key,
                        typ,
                        type.getDescription(),
                        valu);
            }
        } else if (value instanceof TabularData) {
            // I don't pretend to have a good understanding of TabularData.
            // The real world usage doesn't appear to match how they were
            // meant to be used according to the docs. I've only seen them
            // used as 'key' 'value' pairs even when 'value' is itself a
            // CompositeData of multiple values.
            logScrape(domain + beanProperties + attrName, "tabulardata");
            TabularData tds = (TabularData) value;
            TabularType tt = tds.getTabularType();

            List<String> rowKeys = tt.getIndexNames();

            CompositeType type = tt.getRowType();
            Set<String> valueKeys = new TreeSet<String>(type.keySet());
            valueKeys.removeAll(rowKeys);

            LinkedList<String> extendedAttrKeys = new LinkedList<String>(attrKeys);
            extendedAttrKeys.add(attrName);
            for (Object valu : tds.values()) {
                if (valu instanceof CompositeData) {
                    CompositeData composite = (CompositeData) valu;
                    LinkedHashMap<String, String> l2s = new LinkedHashMap<String, String>(beanProperties);
                    for (String idx : rowKeys) {
                        Object obj = composite.get(idx);
                        if (obj != null) {
                            // Nested tabulardata will repeat the 'key' label, so
                            // append a suffix to distinguish each.
                            while (l2s.containsKey(idx)) {
                                idx = idx + "_";
                            }
                            l2s.put(idx, obj.toString());
                        }
                    }
                    for (String valueIdx : valueKeys) {
                        LinkedList<String> attrNames = extendedAttrKeys;
                        String typ = type.getType(valueIdx).getTypeName();
                        String name = valueIdx;
                        if (valueIdx.equalsIgnoreCase("value")) {
                            // Skip appending 'value' to the name
                            attrNames = attrKeys;
                            name = attrName;
                        }
                        processBeanValue(
                                domain,
                                l2s,
                                attrNames,
                                name,
                                typ,
                                type.getDescription(),
                                composite.get(valueIdx));
                    }
                } else {
                    logScrape(domain, "not a correct tabulardata format");
                }
            }
        } else if (value.getClass().isArray()) {
            logScrape(domain, "arrays are unsupported");
        } else if (optionalValueExtractor.isOptional(value)) {
            logScrape(domain + beanProperties + attrName, "java.util.Optional");
            processBeanValue(
                    domain,
                    beanProperties,
                    attrKeys,
                    attrName,
                    attrType,
                    attrDescription,
                    optionalValueExtractor.getOptionalValueOrNull(value));
        } else {
            logScrape(domain + beanProperties, attrType + " is not exported");
        }
    }

    /**
     * For debugging.
     */
    private static void logScrape(ObjectName mbeanName, Set<String> names, String msg) {
        logScrape(mbeanName + "_" + names, msg);
    }

    private static void logScrape(ObjectName mbeanName, MBeanAttributeInfo attr, String msg) {
        logScrape(mbeanName + "'_'" + attr.getName(), msg);
    }

    private static void logScrape(String name, String msg) {
        logger.debug("scrape: '{}': {}", name, msg);
    }

    private static class StdoutWriter implements MBeanReceiver {
        public void recordBean(
                String jmxURL,
                String domain,
                LinkedHashMap<String, String> beanProperties,
                LinkedList<String> attrKeys,
                String attrName,
                String attrType,
                String attrDescription,
                Object value) {
            System.out.println(domain +
                    beanProperties +
                    attrKeys +
                    attrName +
                    ": " + value);
        }
    }

    /**
     * Convenience function to run standalone.
     */
    public static void main(String[] args) throws Exception {
        List<ObjectName> objectNames = new LinkedList<ObjectName>();
        objectNames.add(null);
        if (args.length >= 3) {
            new JmxScraper(args[0], args[1], args[2], false, objectNames, new LinkedList<ObjectName>(),
                    new StdoutWriter(), new JmxMBeanPropertyCache()).doScrape();
        } else if (args.length > 0) {
            new JmxScraper(args[0], "", "", false, objectNames, new LinkedList<ObjectName>(),
                    new StdoutWriter(), new JmxMBeanPropertyCache()).doScrape();
        } else {
            new JmxScraper("", "", "", false, objectNames, new LinkedList<ObjectName>(),
                    new StdoutWriter(), new JmxMBeanPropertyCache()).doScrape();
        }
    }
}

