package com.heroku.agent;

import sun.management.HotspotInternal;
import sun.management.HotspotThreadMBean;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Timer;
import java.util.TimerTask;

public class Agent {

    Timer timer = new Timer("Heroku Agent Timer",/*daemon*/true);
    Instrumentation instrumentation;
    static Agent agent;
    static int BYTES_PER_MB = 1024 * 1000;
    public static final String HEROKU_INTERNAL_MBEAN = "heroku:name=hostpotInternal";
    public static final String HOTSPOT_THREADING_MBEAN = "sun.management:type=HotspotThreading";
    //public static final String HOTSPOT_MEMORY_MBEAN = "sun.management:type=HotspotMemory";


    public static void premain(String agentArgs, Instrumentation instrumentation) {
        agent = new Agent(instrumentation);
    }

    public Agent(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        timer.scheduleAtFixedRate(new Reporter(), 5000, 60000);
    }


    public static class Reporter extends TimerTask {

        static class LazyJMX {
            /*
            we cant register the hotspot mbeans in premain so we do it lazily here so main will have been called
             */

            static MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            static MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

            static {
                try {
                    mBeanServer.registerMBean(new HotspotInternal(), objectName(HEROKU_INTERNAL_MBEAN));
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    throw new RuntimeException("Cant register HostpotInternal MBeans");
                }
            }

            //HotspotMemoryMBean hotspotMemoryMBean = JMX.newMBeanProxy(mBeanServer, objectName(HOTSPOT_MEMORY_MBEAN), HotspotMemoryMBean.class);
            static HotspotThreadMBean hotspotThreadMBean = JMX.newMBeanProxy(mBeanServer, objectName(HOTSPOT_THREADING_MBEAN), HotspotThreadMBean.class);
        }

        @Override
        public void run() {
            reportMemoryUtilization();
            reportThreadUtilization();
        }

        private void reportThreadUtilization() {
            int internal = LazyJMX.hotspotThreadMBean.getInternalThreadCount();
            int totalThreads = LazyJMX.threadMXBean.getThreadCount();
            int daemonThreads = LazyJMX.threadMXBean.getDaemonThreadCount();
            int nonDaemon = totalThreads - daemonThreads;
            formatAndOutput("JVM Threads                : total: %d daemon: %d non-daemon: %d internal: %d", totalThreads + internal, daemonThreads, nonDaemon, internal);
        }

        private void reportMemoryUtilization() {
            MemoryUsage heap = LazyJMX.memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = LazyJMX.memoryMXBean.getNonHeapMemoryUsage();
            formatAndOutput("JVM Memory Usage     (Heap): used: %dM committed: %dM max:%dM", heap.getUsed() / BYTES_PER_MB, heap.getCommitted() / BYTES_PER_MB, heap.getMax() / BYTES_PER_MB);
            formatAndOutput("JVM Memory Usage (Non-Heap): used: %dM committed: %dM max:%dM", nonHeap.getUsed() / BYTES_PER_MB, nonHeap.getCommitted() / BYTES_PER_MB, nonHeap.getMax() / BYTES_PER_MB);
        }


        private void formatAndOutput(String fmt, Object... args) {
            System.out.print(String.format("heroku-agent [%d]: ", System.currentTimeMillis()));
            System.out.println(String.format(fmt, args));
        }
    }

    private static ObjectName objectName(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
    }


}
