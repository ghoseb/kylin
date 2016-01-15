package org.apache.kylin.storage.hbase.util;

import org.apache.commons.lang.StringUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.job.lock.JobLock;
import org.apache.kylin.storage.hbase.HBaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 */
public class ZookeeperJobLock implements JobLock {
    private Logger logger = LoggerFactory.getLogger(ZookeeperJobLock.class);

    private static final String ZOOKEEPER_LOCK_PATH = "/kylin/job_engine/lock";

    private String scheduleID;
    private InterProcessMutex sharedLock;
    private CuratorFramework zkClient;

    @Override
    public boolean lock() {
        this.scheduleID = schedulerId();
        String zkConnectString = HBaseConnection.getZKConnectString();
        logger.info("zk connection string:" + zkConnectString);
        logger.info("schedulerId:" + scheduleID);
        if (StringUtils.isEmpty(zkConnectString)) {
            throw new IllegalArgumentException("ZOOKEEPER_QUORUM is empty!");
        }

        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        this.zkClient = CuratorFrameworkFactory.newClient(zkConnectString, retryPolicy);
        this.zkClient.start();
        this.sharedLock = new InterProcessMutex(zkClient, this.scheduleID);
        boolean hasLock = false;
        try {
            hasLock = sharedLock.acquire(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("error acquire lock", e);
        }
        if (!hasLock) {
            logger.warn("fail to acquire lock, scheduler has not been started");
            zkClient.close();
            return false;
        }
        return true;
    }

    @Override
    public void unlock() {
        releaseLock();
    }

    private void releaseLock() {
        try {
            if (zkClient.getState().equals(CuratorFrameworkState.STARTED)) {
                // client.setData().forPath(ZOOKEEPER_LOCK_PATH, null);
                if (zkClient.checkExists().forPath(scheduleID) != null) {
                    zkClient.delete().guaranteed().deletingChildrenIfNeeded().forPath(scheduleID);
                }
            }
        } catch (Exception e) {
            logger.error("error release lock:" + scheduleID);
            throw new RuntimeException(e);
        }
    }

    private String schedulerId() {
        return ZOOKEEPER_LOCK_PATH + "/" + KylinConfig.getInstanceFromEnv().getMetadataUrlPrefix();
    }
}
