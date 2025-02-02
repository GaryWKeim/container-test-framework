/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.util.TcConfigBuilder;

import junit.framework.Test;
import junit.framework.TestSuite;

public abstract class AbstractOneServerDeploymentTest extends AbstractDeploymentTestCase {
  public WebApplicationServer server0;

  public AbstractOneServerDeploymentTest() {
    if (commitTimeoutTaskAdded(false, true)) {
      scheduleTimeoutTask();
    }
  }

  public void setServer0(WebApplicationServer server0) {
    this.server0 = server0;
  }

  @Override
  protected boolean shouldKillAppServersEachRun() {
    return false;
  }

  public static abstract class OneServerTestSetup extends ServerTestSetup {
    private final Log              logger = LogFactory.getLog(getClass());
    private final String           context;

    protected WebApplicationServer server0;

    protected OneServerTestSetup(Class testClass, String context) {
      super(testClass);
      this.context = context;
    }

    protected OneServerTestSetup(Class testClass, String tcConfigFromResource, String context) {
      super(testClass, new TcConfigBuilder(tcConfigFromResource));
      this.context = context;
    }

    protected boolean autostart() {
      return true;
    }

    @Override
    protected void setUp() throws Exception {
      if (shouldDisable()) return;
      super.setUp();
      try {
        getServerManager();

        long l1 = System.currentTimeMillis();
        Deployment deployment = makeWAR();
        long l2 = System.currentTimeMillis();
        logger.info("### WAR build " + (l2 - l1) / 1000f + " at " + deployment.getFileSystemPath());

        configureTcConfig(getTcConfigBuilder());
        server0 = createServer(deployment);

        TestSuite suite = (TestSuite) getTest();
        for (int i = 0; i < suite.testCount(); i++) {
          Test t = suite.testAt(i);
          if (t instanceof AbstractOneServerDeploymentTest) {
            AbstractOneServerDeploymentTest test = (AbstractOneServerDeploymentTest) t;
            test.setServer0(server0);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        ServerManager sm = getServerManager();
        if (sm != null) {
          sm.stop();
        }
        throw e;
      }
    }

    private WebApplicationServer createServer(Deployment deployment) throws Exception {
      WebApplicationServer server = getServerManager().makeWebApplicationServer(getTcConfigBuilder());
      server.addWarDeployment(deployment, context);
      configureServer(server);
      configureServerParamers(server.getServerParameters());
      if (autostart()) {
        server.start();
      }
      return server;
    }

    private Deployment makeWAR() throws Exception {
      DeploymentBuilder builder = makeDeploymentBuilder(this.context + ".war");
      builder.addDirectoryOrJARContainingClass(getTestClass());
      configureWar(builder);
      return builder.makeDeployment();
    }

    protected abstract void configureWar(DeploymentBuilder builder);

    protected void configureTcConfig(TcConfigBuilder clientConfig) {
      // override this method to modify tc-config.xml
    }

    protected void configureServerParamers(StandardAppServerParameters params) {
      // override this method to modify jvm args for app server
    }

    protected void configureServer(WebApplicationServer server) {
      //
    }

  }

}
