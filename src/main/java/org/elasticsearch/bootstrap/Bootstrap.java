/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import com.google.common.base.Charsets;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.inject.spi.Message;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.jna.Natives;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.log4j.LogConfigurator;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.process.JmxProcessProbe;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.node.internal.InternalSettingsPreparer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static com.google.common.collect.Sets.newHashSet;
import static org.elasticsearch.common.jna.Kernel32Library.ConsoleCtrlHandler;
import static org.elasticsearch.common.settings.ImmutableSettings.Builder.EMPTY_SETTINGS;

/**
 * A main entry point when starting from the command line.
 */
public class Bootstrap {

    private Node node;

    private static volatile Thread keepAliveThread;
    private static volatile CountDownLatch keepAliveLatch;

    private static Bootstrap bootstrap;

    private void setup(boolean addShutdownHook, Tuple<Settings, Environment> tuple) throws Exception {
        if (tuple.v1().getAsBoolean("bootstrap.mlockall", false)) {
            Natives.tryMlockall();
        }

        NodeBuilder nodeBuilder = NodeBuilder.nodeBuilder().settings(tuple.v1()).loadConfigSettings(false);
        node = nodeBuilder.build();
        if (addShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    node.close();
                }
            });
        }

        if (tuple.v1().getAsBoolean("bootstrap.ctrlhandler", true)) {
            Natives.addConsoleCtrlHandler(new ConsoleCtrlHandler() {
                @Override
                public boolean handle(int code) {
                    if (CTRL_CLOSE_EVENT == code) {
                        ESLogger logger = Loggers.getLogger(Bootstrap.class);
                        logger.info("running graceful exit on windows");

                        System.exit(0);
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    private static void setupLogging(Tuple<Settings, Environment> tuple) {
        try {
            tuple.v1().getClassLoader().loadClass("org.apache.log4j.Logger");
            LogConfigurator.configure(tuple.v1());
        } catch (ClassNotFoundException e) {
            // no log4j
        } catch (NoClassDefFoundError e) {
            // no log4j
        } catch (Exception e) {
            System.err.println("Failed to configure logging...");
            e.printStackTrace();
        }
    }

    private static Tuple<Settings, Environment> initialSettings() {
        return InternalSettingsPreparer.prepareSettings(EMPTY_SETTINGS, true);
    }

    /**
     * hook for JSVC
     */
    public void init(String[] args) throws Exception {
        Tuple<Settings, Environment> tuple = initialSettings();
        setupLogging(tuple);
        setup(true, tuple);
    }

    /**
     * hook for JSVC
     */
    public void start() {
        node.start();
    }

    /**
     * hook for JSVC
     */
    public void stop() {
        node.stop();
    }


    /**
     * hook for JSVC
     */
    public void destroy() {
        node.close();
    }

    public static void close(String[] args) {
        bootstrap.destroy();
        keepAliveLatch.countDown();
    }

    public static void main(String[] args) {
        System.setProperty("es.logger.prefix", "");
        bootstrap = new Bootstrap();
        final String pidFile = System.getProperty("es.pidfile", System.getProperty("es-pidfile"));

        if (pidFile != null) {
            try {
                File fPidFile = new File(pidFile);
                if (fPidFile.getParentFile() != null) {
                    FileSystemUtils.mkdirs(fPidFile.getParentFile());
                }
                FileOutputStream outputStream = new FileOutputStream(fPidFile);
                outputStream.write(Long.toString(JvmInfo.jvmInfo().pid()).getBytes(Charsets.UTF_8));
                outputStream.close();

                fPidFile.deleteOnExit();
            } catch (Exception e) {
                String errorMessage = buildErrorMessage("pid", e);
                System.err.println(errorMessage);
                System.err.flush();
                System.exit(3);
            }
        }

        boolean foreground = System.getProperty("es.foreground", System.getProperty("es-foreground")) != null;
        // handle the wrapper system property, if its a service, don't run as a service
        if (System.getProperty("wrapper.service", "XXX").equalsIgnoreCase("true")) {
            foreground = false;
        }

        Tuple<Settings, Environment> tuple = null;
        try {
            tuple = initialSettings();
            setupLogging(tuple);
        } catch (Exception e) {
            String errorMessage = buildErrorMessage("Setup", e);
            System.err.println(errorMessage);
            System.err.flush();
            System.exit(3);
        }

        if (System.getProperty("es.max-open-files", "false").equals("true")) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.info("max_open_files [{}]", JmxProcessProbe.getMaxFileDescriptorCount());
        }

        // warn if running using the client VM
        if (JvmInfo.jvmInfo().vmName().toLowerCase(Locale.ROOT).contains("client")) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.warn("jvm uses the client vm, make sure to run `java` with the server vm for best performance by adding `-server` to the command line");
        }

        String stage = "Initialization";
        try {
            if (!foreground) {
                Loggers.disableConsoleLogging();
                System.out.close();
            }

            // fail if using broken version
            JVMCheck.check();

            bootstrap.setup(true, tuple);

            stage = "Startup";
            bootstrap.start();

            if (!foreground) {
                System.err.close();
            }

            keepAliveLatch = new CountDownLatch(1);
            // keep this thread alive (non daemon thread) until we shutdown
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    keepAliveLatch.countDown();
                }
            });

            keepAliveThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        keepAliveLatch.await();
                    } catch (InterruptedException e) {
                        // bail out
                    }
                }
            }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
            keepAliveThread.setDaemon(false);
            keepAliveThread.start();
        } catch (Throwable e) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            if (bootstrap.node != null) {
                logger = Loggers.getLogger(Bootstrap.class, bootstrap.node.settings().get("name"));
            }
            String errorMessage = buildErrorMessage(stage, e);
            if (foreground) {
                System.err.println(errorMessage);
                System.err.flush();
                Loggers.disableConsoleLogging();
            }
            logger.error("Exception", e);
            
            System.exit(3);
        }
    }

    private static String buildErrorMessage(String stage, Throwable e) {
        StringBuilder errorMessage = new StringBuilder("{").append(Version.CURRENT).append("}: ");
        errorMessage.append(stage).append(" Failed ...\n");
        if (e instanceof CreationException) {
            CreationException createException = (CreationException) e;
            Set<String> seenMessages = newHashSet();
            int counter = 1;
            for (Message message : createException.getErrorMessages()) {
                String detailedMessage;
                if (message.getCause() == null) {
                    detailedMessage = message.getMessage();
                } else {
                    detailedMessage = ExceptionsHelper.detailedMessage(message.getCause(), true, 0);
                }
                if (detailedMessage == null) {
                    detailedMessage = message.getMessage();
                }
                if (seenMessages.contains(detailedMessage)) {
                    continue;
                }
                seenMessages.add(detailedMessage);
                errorMessage.append("").append(counter++).append(") ").append(detailedMessage);
            }
        } else {
            errorMessage.append("- ").append(ExceptionsHelper.detailedMessage(e, true, 0));
        }
        if (Loggers.getLogger(Bootstrap.class).isDebugEnabled()) {
            errorMessage.append("\n").append(ExceptionsHelper.stackTrace(e));
        }
        return errorMessage.toString();
    }
}
