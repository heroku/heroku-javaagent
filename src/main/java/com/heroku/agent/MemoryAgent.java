package com.heroku.agent;

import sun.management.HotspotInternal;
import sun.management.HotspotThreadMBean;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.util.*;


public class MemoryAgent {

    Timer timer = new Timer("Heroku MemoryAgent Timer",/*daemon*/true);
    Instrumentation instrumentation;
    static MemoryAgent agent;
    static int BYTES_PER_MB = 1024 * 1000;
    static int KB_PER_MB = 1024;
    public static final String HEROKU_INTERNAL_MBEAN = "heroku:name=hostpotInternal";
    public static final String HOTSPOT_THREADING_MBEAN = "sun.management:type=HotspotThreading";
    //public static final String HOTSPOT_MEMORY_MBEAN = "sun.management:type=HotspotMemory";


    public static void premain(String agentArgs, Instrumentation instrumentation) {
        boolean userlog = agentArgs != null && agentArgs.contains("stdout=true");
        boolean linuxMem = agentArgs != null && agentArgs.contains("lxmem=true");
        boolean legacyFmt = agentArgs != null && agentArgs.contains("legacyfmt=true");
        agent = new MemoryAgent(instrumentation, userlog, linuxMem, legacyFmt);
    }

    public MemoryAgent(Instrumentation instrumentation, boolean userlog, boolean linuxMem, boolean legacyFmt) {
        this.instrumentation = instrumentation;
        timer.scheduleAtFixedRate(new Reporter(userlog, linuxMem, legacyFmt), 5000, 60000);
    }


    public static class Reporter extends TimerTask {

        private boolean userlog;
        private boolean linuxMem;
        private boolean legacyFmt;

        public Reporter(boolean userlog, boolean linuxMem, boolean legacyFmt) {
            this.userlog = userlog;
            this.linuxMem = linuxMem;
            this.legacyFmt = legacyFmt;
        }

        static enum Attribute {
            heap_memory_used_mb("Heap Memory Used"),
            heap_memory_committed_mb("Heap Memory Committed"),
            heap_memory_max_mb("Heap Memory Max"),
            nonheap_memory_used_mb("Non-Heap Memory Used"),
            nonheap_memory_committed_mb("Non-Heap Memory Committed"),
            nonheap_memory_max_mb("Non-Heap Memory Maximum"),
            total_threads("Total Threads"),
            daemon_threads("Daemon Threads"),
            nondaemon_threads("Non-Daemon Threads"),
            internal_threads("Internal Threads (GC, etc)"),
            direct_buffers_used("Direct Buffers Used"),
            direct_buffers_capacity("Direct Buffers Capacity"),
            direct_buffers_count("Direct Buffers Count"),
            mapped_buffers_used("Mapped Buffers Used"),
            mapped_buffers_capacity("Mapped Buffers Capacity"),
            mapped_buffers_count("Mapped Buffers Count");

            public final String pretty;

            Attribute(String pretty) {
                this.pretty = pretty;
            }
        }

        static class LazyJMX {

            /*
           we cant register the hotspot mbeans in premain so we do it lazily here so main will have been called
            */

            static MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            static MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            static List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

            static {
                try {
                    mBeanServer.registerMBean(new HotspotInternal(), objectName(HEROKU_INTERNAL_MBEAN));
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    throw new RuntimeException("Cant register HostpotInternal MBeans");
                }
            }

            static HotspotThreadMBean hotspotThreadMBean = JMX.newMBeanProxy(mBeanServer, objectName(HOTSPOT_THREADING_MBEAN), HotspotThreadMBean.class);
        }

        static class LazyLogger {

            static Process logger;
            static PrintStream out;

            static {
                try {
                    logger = new ProcessBuilder("logger", "-t", "heroku-javaagent").start();
                    out = new PrintStream(logger.getOutputStream());
                } catch (IOException e) {
                    System.out.println("Cant init syslogger process, skipping");
                    out = new PrintStream(new OutputStream() {
                        @Override
                        public void write(int i) throws IOException {
                            //noop
                        }
                    });
                }
            }

        }

        @Override
        public void run() {
            EnumMap<Attribute, Long> attributes = new EnumMap<Attribute, Long>(Attribute.class);
            getMemoryUtilization(attributes);
            getThreadUtilization(attributes);
            getDirectBufferPools(attributes);
            getMappedBufferPools(attributes);
            if (userlog) {
                if(legacyFmt) {
                    userReportLegacy(attributes);
                } else {
                    userReport(attributes);
                }
            }
            statsReport(attributes);
            if(linuxMem) {
                try {
                    linuxMemReport();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void userReport(EnumMap<Attribute, Long> attributes) {
            formatAndOutput("measure.mem.jvm.heap.used=%dM measure.mem.jvm.heap.committed=%dM measure.mem.jvm.heap.max=%dM",
                attributes.get(Attribute.heap_memory_used_mb),
                attributes.get(Attribute.heap_memory_committed_mb),
                attributes.get(Attribute.heap_memory_max_mb));
            formatAndOutput("measure.mem.jvm.nonheap.used=%dM measure.mem.jvm.nonheap.committed=%dM measure.mem.jvm.nonheap.max=%dM",
                attributes.get(Attribute.nonheap_memory_used_mb),
                attributes.get(Attribute.nonheap_memory_committed_mb),
                attributes.get(Attribute.nonheap_memory_max_mb));
            formatAndOutput("measure.mem.jvm.%1$2s.used=%2$dM measure.mem.jvm.%1$2s.count=%3$d measure.mem.jvm.%1$2s.capacity=%4$dM",
                "direct",
                attributes.get(Attribute.direct_buffers_used),
                attributes.get(Attribute.direct_buffers_capacity),
                attributes.get(Attribute.direct_buffers_count));
            formatAndOutput("measure.mem.jvm.%1$2s.used=%2$dM measure.mem.jvm.%1$2s.count=%3$d measure.mem.jvm.%1$2s.capacity=%4$dM",
                "mapped",
                attributes.get(Attribute.mapped_buffers_used),
                attributes.get(Attribute.mapped_buffers_capacity),
                attributes.get(Attribute.mapped_buffers_count));
            formatAndOutput("measure.threads.jvm.total=%d measure.threads.jvm.daemon=%d measure.threads.jvm.nondaemon=%d measure.threads.jvm.internal=%d",
                attributes.get(Attribute.total_threads),
                attributes.get(Attribute.daemon_threads),
                attributes.get(Attribute.nondaemon_threads),
                attributes.get(Attribute.internal_threads));
        }
        
        private void userReportLegacy(EnumMap<Attribute, Long> attributes) {
            formatAndOutput("JVM Memory Usage     (Heap): used: %dM committed: %dM max:%dM",
                    attributes.get(Attribute.heap_memory_used_mb), attributes.get(Attribute.heap_memory_committed_mb), attributes.get(Attribute.heap_memory_max_mb));
            formatAndOutput("JVM Memory Usage (Non-Heap): used: %dM committed: %dM max:%dM",
                    attributes.get(Attribute.nonheap_memory_used_mb), attributes.get(Attribute.nonheap_memory_committed_mb), attributes.get(Attribute.nonheap_memory_max_mb));
            formatAndOutput("JVM Threads                : total: %d daemon: %d non-daemon: %d internal: %d",
                    attributes.get(Attribute.total_threads), attributes.get(Attribute.daemon_threads), attributes.get(Attribute.nondaemon_threads), attributes.get(Attribute.internal_threads));
        }
        
        private void linuxMemReport() throws Exception {

            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String commandString = "ps auxwww | awk '$2==" + pid + "{print $5, $6}'";
            String[] cmd = {
                    "/bin/sh",
                    "-c",
                    commandString
                    };
            Process p = Runtime.getRuntime().exec(cmd);
           
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    p.getInputStream()));
 
            String line = reader.readLine();
            
            String[] memValues = line.split(" ");
            if(memValues.length > 1) {
                Integer vsz = Integer.valueOf(memValues[0]);
                Integer rss = Integer.valueOf(memValues[1]);
                
                formatAndOutput("measure.mem.linux.vsz=%dM measure.mem.linux.rss=%dM", vsz/KB_PER_MB, rss/KB_PER_MB);
            } else {
                formatAndOutput("measure.mem.linux.vsz=%dM measure.mem.linux.rss=%dM", 0d, 0d);
            }
            
        }
        
        private String getPid() throws Exception {
            
            String[] cmd = {
                    "/bin/sh",
                    "-c",
                    "jps | awk '$2!=\"Jps\"{print $1}'"
            };
            Process p = Runtime.getRuntime().exec(cmd);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    p.getInputStream()));
            
            String pid = reader.readLine();
            return pid;
        }

        private void statsReport(EnumMap<Attribute, Long> attributes) {
            StringBuilder stats = new StringBuilder("{ heroku-javaagent: {");
            for (Map.Entry<Attribute, Long> entry : attributes.entrySet()) {
                stats.append(entry.getKey().name()).append("=").append(entry.getValue()).append(", ");
            }
            stats.setLength(stats.length() - ", ".length());
            stats.append("} }");
            LazyLogger.out.println(stats.toString());
            LazyLogger.out.flush();
        }

        private void getThreadUtilization(EnumMap<Attribute, Long> attributes) {
            int internal = LazyJMX.hotspotThreadMBean.getInternalThreadCount();
            int totalThreads = LazyJMX.threadMXBean.getThreadCount();
            int daemonThreads = LazyJMX.threadMXBean.getDaemonThreadCount();
            int nonDaemon = totalThreads - daemonThreads;
            attributes.put(Attribute.total_threads, new Long(totalThreads + internal));
            attributes.put(Attribute.daemon_threads, new Long(daemonThreads));
            attributes.put(Attribute.nondaemon_threads, new Long(nonDaemon));
            attributes.put(Attribute.internal_threads, new Long(internal));
        }

        private void getMemoryUtilization(EnumMap<Attribute, Long> attributes) {
            MemoryUsage heap = LazyJMX.memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = LazyJMX.memoryMXBean.getNonHeapMemoryUsage();
            attributes.put(Attribute.heap_memory_used_mb, heap.getUsed() / BYTES_PER_MB);
            attributes.put(Attribute.heap_memory_committed_mb, heap.getCommitted() / BYTES_PER_MB);
            attributes.put(Attribute.heap_memory_max_mb, heap.getMax() / BYTES_PER_MB);
            attributes.put(Attribute.nonheap_memory_used_mb, nonHeap.getUsed() / BYTES_PER_MB);
            attributes.put(Attribute.nonheap_memory_committed_mb, nonHeap.getCommitted() / BYTES_PER_MB);
            attributes.put(Attribute.nonheap_memory_max_mb, nonHeap.getMax() / BYTES_PER_MB);
        }

        private void getDirectBufferPools(EnumMap<Attribute, Long> attributes) {
            for (BufferPoolMXBean bean : LazyJMX.bufferPools) {
               if ("direct".equals(bean.getName())) {
                    attributes.put(Attribute.direct_buffers_count, bean.getCount());
                    attributes.put(Attribute.direct_buffers_used, bean.getMemoryUsed() / BYTES_PER_MB);
                    attributes.put(Attribute.direct_buffers_capacity, bean.getTotalCapacity() / BYTES_PER_MB);
                }
            }
        }

        private void getMappedBufferPools(EnumMap<Attribute, Long> attributes) {
            for (BufferPoolMXBean bean : LazyJMX.bufferPools) {
                if ("mapped".equals(bean.getName())) {
                    attributes.put(Attribute.mapped_buffers_count, bean.getCount());
                    attributes.put(Attribute.mapped_buffers_used, bean.getMemoryUsed() / BYTES_PER_MB);
                    attributes.put(Attribute.mapped_buffers_capacity, bean.getTotalCapacity() / BYTES_PER_MB);
                }
            }
        }

        private void formatAndOutput(String fmt, Object... args) {
            String msg = String.format(fmt, args);
            if(legacyFmt) {
                System.out.print("heroku-javaagent: "); 
            } else {                
                if(System.getenv("PS") != null) {                
                    System.out.print("source=" + System.getenv("PS") + " ");
                }
            }
            System.out.println(msg);
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
