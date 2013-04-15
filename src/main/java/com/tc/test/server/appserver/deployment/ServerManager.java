/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.terracotta.test.util.TestBaseUtil;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.AppServerFactory;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.util.AppServerUtil;
import com.tc.test.server.util.Util;
import com.tc.util.PortChooser;
import com.tc.util.TcConfigBuilder;
import com.tc.util.runtime.Os;
import com.tc.util.runtime.Vm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

public class ServerManager {
  private static final String         SESSIONS_LOAD_CLASS        = "com.terracotta.session.BaseExpressSessionFilter";

  private static final String         TOOLKIT_RUNTIME_LOAD_CLASS = "org.terracotta.toolkit.ToolkitFactory";

  protected final static TCLogger     logger                     = TCLogging.getLogger(ServerManager.class);
  private static int                  appServerIndex             = 0;
  private final boolean               DEBUG_MODE                 = false;

  private List                        serversToStop              = new ArrayList();
  private DSOServer                   dsoServer;

  private final TestConfigObject      config;
  private final AppServerFactory      factory;
  private final AppServerInstallation installation;
  private final File                  sandbox;
  private final File                  tempDir;
  private final File                  installDir;
  private final File                  warDir;
  private final File                  tcConfigFile;
  private final TcConfigBuilder       serverTcConfig             = new TcConfigBuilder();
  private final Collection            jvmArgs;

  private static int                  serverCounter              = 0;
  private final Boolean               isSynchronousWrite;
  private final Boolean               isSessionLocking;
  private final boolean               isSessionTest;

  public ServerManager(final Class testClass, final Collection extraJvmArgs, Boolean isSessionLocking,
                       Boolean isSynchronousWrite) throws Exception {
    this.isSessionLocking = isSessionLocking;
    this.isSynchronousWrite = isSynchronousWrite;
    this.isSessionTest = isTerracottaSessionJarPresent();

    config = TestConfigObject.getInstance();
    factory = AppServerFactory.createFactoryFromProperties();
    installDir = config.appserverServerInstallDir();
    tempDir = TempDirectoryUtil.getTempDirectory(testClass);
    tcConfigFile = new File(tempDir, "tc-config.xml");
    sandbox = AppServerUtil.createSandbox(tempDir);

    warDir = new File(sandbox, "war");
    jvmArgs = extraJvmArgs;
    installation = AppServerUtil.createAppServerInstallation(factory, installDir, sandbox);

    if (DEBUG_MODE) {
      serverTcConfig.setTsaPort(9510);
      serverTcConfig.setJmxPort(9520);
      serverTcConfig.setGroupPort(9530);
    } else {
      PortChooser pc = new PortChooser();
      serverTcConfig.setTsaPort(pc.chooseRandomPort());
      serverTcConfig.setJmxPort(pc.chooseRandomPort());
      serverTcConfig.setGroupPort(pc.chooseRandomPort());
    }
  }

  private boolean isTerracottaSessionJarPresent() {
    try {
      Class.forName(SESSIONS_LOAD_CLASS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public void addServerToStop(final Stoppable stoppable) {
    getServersToStop().add(0, stoppable);
  }

  void stop() {
    logger.info("Stopping all servers");
    for (Iterator it = getServersToStop().iterator(); it.hasNext();) {
      Stoppable stoppable = (Stoppable) it.next();
      try {
        if (!stoppable.isStopped()) {
          logger.debug("About to stop server: " + stoppable.toString());
          stoppable.stop();
        }
      } catch (Exception e) {
        logger.error(stoppable, e);
      }
    }

    AppServerUtil.shutdownAndArchive(sandbox, new File(tempDir, "sandbox"));
  }

  void timeout() {
    System.err.println("Test has timed out. Force shutdown and archive...");
    AppServerUtil.forceShutdownAndArchive(sandbox, new File(tempDir, "sandbox"));
  }

  protected boolean cleanTempDir() {
    return false;
  }

  void start(final boolean withPersistentStore) throws Exception {
    startDSO(withPersistentStore);
  }

  private void startDSO(final boolean withPersistentStore) throws Exception {
    File workDir = new File(tempDir, "dso-server-" + serverCounter++);
    workDir.mkdirs();
    dsoServer = new DSOServer(withPersistentStore, workDir, serverTcConfig);
    if (!Vm.isIBM() && !(Os.isMac() && Vm.isJDK14())) {
      dsoServer.getJvmArgs().add("-XX:+HeapDumpOnOutOfMemoryError");
    }

    if (!Vm.isIBM()) {
      dsoServer.getJvmArgs().add("-verbose:gc");

      if (!Vm.isJRockit()) {
        dsoServer.getJvmArgs().add("-XX:+PrintGCDetails");
        dsoServer.getJvmArgs().add("-XX:+PrintGCTimeStamps");
      }

      final String gcLogSwitch;
      if (Vm.isJRockit()) {
        gcLogSwitch = "verboselog";
      } else {
        gcLogSwitch = "loggc";
      }

      dsoServer.getJvmArgs().add("-X" + gcLogSwitch + ":" + new File(workDir, "dso-server-gc.log").getAbsolutePath());
    }

    dsoServer.getJvmArgs().add("-Xmx384m");

    for (Iterator iterator = jvmArgs.iterator(); iterator.hasNext();) {
      dsoServer.getJvmArgs().add(iterator.next());
    }

    logger.debug("Starting DSO server with sandbox: " + sandbox.getAbsolutePath());
    dsoServer.start();
    addServerToStop(dsoServer);
  }

  public void restartDSO(final boolean withPersistentStore) throws Exception {
    logger.debug("Restarting DSO server : " + dsoServer);
    dsoServer.stop();
    startDSO(withPersistentStore);
  }

  public WebApplicationServer makeWebApplicationServer(final TcConfigBuilder tcConfigBuilder) throws Exception {
    return makeWebApplicationServer(tcConfigBuilder, true);
  }

  public WebApplicationServer makeWebApplicationServer(final TcConfigBuilder tcConfigBuilder, boolean clustered)
      throws Exception {
    int i = ServerManager.appServerIndex++;

    WebApplicationServer appServer = new GenericServer(config, factory, installation,
                                                       prepareClientTcConfig(tcConfigBuilder).getTcConfigFile(), i,
                                                       tempDir);
    if (clustered) {
      addExpressModeParams(appServer.getServerParameters());
    }
    addServerToStop(appServer);
    return appServer;
  }

  public WebApplicationServer makeWebApplicationServerNoDso() throws Exception {
    int i = ServerManager.appServerIndex++;
    WebApplicationServer appServer = new GenericServer(config, factory, installation, null, i, tempDir);
    addExpressModeParams(appServer.getServerParameters());
    addServerToStop(appServer);
    return appServer;

  }

  public FileSystemPath getTcConfigFile(final String tcConfigPath) {
    URL url = getClass().getResource(tcConfigPath);
    Assert.assertNotNull("could not find: " + tcConfigPath, url);
    Assert.assertTrue("should be file:" + url.toString(), url.toString().startsWith("file:"));
    FileSystemPath pathToTcConfigFile = FileSystemPath.makeExistingFile(url.toString().substring("file:".length()));
    return pathToTcConfigFile;
  }

  private TcConfigBuilder prepareClientTcConfig(final TcConfigBuilder clientConfig) throws IOException {
    TcConfigBuilder aCopy = clientConfig.copy();
    aCopy.setTcConfigFile(tcConfigFile);
    aCopy.setTsaPort(getServerTcConfig().getTsaPort());
    aCopy.setJmxPort(getServerTcConfig().getJmxPort());

    aCopy.saveToFile();
    return aCopy;
  }

  void setServersToStop(final List serversToStop) {
    this.serversToStop = serversToStop;
  }

  List getServersToStop() {
    return serversToStop;
  }

  public DeploymentBuilder makeDeploymentBuilder(final String warFileName) {
    return makeDeploymentBuilder(warFileName, true);
  }

  public DeploymentBuilder makeDeploymentBuilder(final String warFileName, boolean clustered) {
    DeploymentBuilder builder = new WARBuilder(warFileName, warDir, config, clustered);

    if (clustered) {
      addExpressModeWarConfig(builder);
    }
    // File format for the jboss-web.xml changed in jboss7, so change it here
    // TODO: change how the default is picked up so we don't need to have this sort of logic for jboss8+?
    if (isJboss7x()) {
      builder.addFileAsResource(makeEmptyJboss7WebXml(), "WEB-INF");
    }
    return builder;
  }

  public void stopAllWebServers() {
    for (Iterator it = getServersToStop().iterator(); it.hasNext();) {
      Stoppable stoppable = (Stoppable) it.next();
      try {
        if (!(stoppable instanceof DSOServer || stoppable.isStopped())) stoppable.stop();
      } catch (Exception e) {
        logger.error("Unable to stop server: " + stoppable, e);
      }
    }
  }

  public TestConfigObject getTestConfig() {
    return this.config;
  }

  public File getSandbox() {
    return sandbox;
  }

  public File getTempDir() {
    return tempDir;
  }

  public TcConfigBuilder getServerTcConfig() {
    return serverTcConfig;
  }

  public File getTcConfigFile() {
    return tcConfigFile;
  }

  @Override
  public String toString() {
    return "ServerManager{" + "dsoServer=" + dsoServer.toString() + ", sandbox=" + sandbox.getAbsolutePath()
           + ", warDir=" + warDir.getAbsolutePath() + ", jvmArgs=" + jvmArgs + '}';
  }

  private String getTcConfigUrl() {
    return "localhost:" + serverTcConfig.getTsaPort();
  }

  private boolean useFilter() {
    return true;
  }

  private boolean isJboss7x() {
    return (config.appServerId() == AppServerInfo.JBOSS && config.appServerInfo().getMajor().equals("7"));
  }

  private Map<String, String> getConfigAttributes() {
    Map<String, String> attrs = new HashMap();
    attrs.put("tcConfigUrl", getTcConfigUrl());
    System.out.println("XXX: sessionLocking: " + isSessionLocking);
    System.out.println("XXX: synchronousWrite: " + isSynchronousWrite);
    if (isSessionLocking != null) {
      attrs.put("sessionLocking", isSessionLocking.toString());
    }
    if (isSynchronousWrite != null) {
      attrs.put("synchronousWrite", isSynchronousWrite.toString());
    }
    return attrs;
  }

  private void addExpressModeParams(StandardAppServerParameters params) {
    setupExpressJarContaining(params, TOOLKIT_RUNTIME_LOAD_CLASS);

    if (isSessionTest) {
      setupExpressJarContaining(params, SESSIONS_LOAD_CLASS);
    }
  }

  private void setupExpressJarContaining(StandardAppServerParameters params, String className) {
    File expressJar;
    try {
      expressJar = new File(Util.jarFor(Class.forName(className)));
    } catch (ClassNotFoundException e1) {
      throw new RuntimeException("Couldn't load class " + className + " to look up the jar file", e1);
    }
    File sandBoxArtifact = new File(this.tempDir, expressJar.getName());

    if (!sandBoxArtifact.exists()) {
      // copy the express jar into the test temp dir since that likely won't have any spaces in it
      try {
        FileUtils.copyFile(expressJar, sandBoxArtifact);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    params.addTomcatServerJar(sandBoxArtifact.getAbsolutePath());
  }

  private void addExpressModeWarConfig(DeploymentBuilder builder) {
    if (isSessionTest) {
      if (useFilter()) {
        try {
          builder.addDirectoryOrJARContainingClass(Class.forName(SESSIONS_LOAD_CLASS));
          builder.addDirectoriesOrJARs(TestBaseUtil.getToolkitRuntimeDependencies(Class
              .forName(TOOLKIT_RUNTIME_LOAD_CLASS)));
        } catch (ClassNotFoundException e1) {
          throw new RuntimeException(e1);
        }

        Map<String, String> filterConfig = getConfigAttributes();

        Class filter;
        try {
          filter = Class.forName(Mappings.getClassForAppServer(config.appServerInfo()));
        } catch (ClassNotFoundException e) {
          throw new RuntimeException(e);
        }
        builder.addFilter("terracotta-filter", "/*", filter, filterConfig, EnumSet.allOf(WARBuilder.Dispatcher.class));
      }

      // not using filter, but jboss7 still needs the express jars
      if (isJboss7x()) {
        try {
          builder.addDirectoryOrJARContainingClass(Class.forName(SESSIONS_LOAD_CLASS));
          builder.addDirectoryOrJARContainingClass(Class.forName(TOOLKIT_RUNTIME_LOAD_CLASS));
        } catch (ClassNotFoundException e1) {
          throw new RuntimeException(e1);
        }
      }
    }
  }

  private File makeEmptyJboss7WebXml() {
    File tmp = new File(this.sandbox, "jboss-web.xml");
    String xml = "";
    xml += "<jboss-web>\n";
    xml += "</jboss-web>\n";

    return writeJbossXml(xml, tmp);
  }

  private File writeJbossXml(String xml, File target) {
    FileOutputStream out = null;
    try {
      out = new FileOutputStream(target);
      out.write(xml.getBytes());
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    } finally {
      IOUtils.closeQuietly(out);
    }
    return target;
  }

  private static class Mappings {
    private static final Map<String, String> mappings = new HashMap<String, String>();

    static {
      mappings.put("jboss-4.0.", "TerracottaJboss71xSessionFilter");
      mappings.put("jboss-4.2.", "TerracottaJboss71xSessionFilter");
      mappings.put("jboss-5.1.", "TerracottaJboss71xSessionFilter");
      mappings.put("jboss-6.0.", "TerracottaJboss71xSessionFilter");
      mappings.put("jboss-7.1.", "TerracottaJboss71xSessionFilter");
      mappings.put("weblogic-10.3.", "TerracottaWeblogic103xSessionFilter");
      mappings.put("weblogic-12.1.", "TerracottaWeblogic121xSessionFilter");
      mappings.put("jetty-7.4.", "TerracottaJetty74xSessionFilter");
      mappings.put("tomcat-5.0.", "TerracottaTomcat60xSessionFilter");
      mappings.put("tomcat-5.5.", "TerracottaTomcat60xSessionFilter");
      mappings.put("tomcat-6.0.", "TerracottaTomcat60xSessionFilter");
      mappings.put("tomcat-7.0.", "TerracottaTomcat70xSessionFilter");
      mappings.put("websphere-7.0.", "TerracottaWebsphere70xSessionFilter");
      mappings.put("websphere-8.0.", "TerracottaWebsphere80xSessionFilter");
      mappings.put("websphere-8.5.", "TerracottaWebsphere85xSessionFilter");
      mappings.put("resin-4.0.", "TerracottaResin40xSessionFilter");
      mappings.put("glassfish-3.1.", "TerracottaGlassfish31xSessionFilter");
    }

    static String getClassForAppServer(AppServerInfo info) {
      for (String key : mappings.keySet()) {
        if (info.toString().startsWith(key)) { return "org.terracotta.session." + mappings.get(key); }

      }

      throw new AssertionError("no mapping for " + info);
    }
  }
}
