/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import java.util.Properties;

public interface Deployment {
  public FileSystemPath getFileSystemPath();

  public boolean isClustered();

  public Properties properties();
}
