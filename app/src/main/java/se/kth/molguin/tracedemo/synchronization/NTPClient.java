package se.kth.molguin.tracedemo.synchronization;

/*
Manuel Olguin, May 2 2018: Shamelessly pulled from
https://github.com/wshackle/ntpclient/blob/master/src/main/java/com/github/wshackle/ntpclient/NTPClient.java
Modified to suit the use cases of our application.

This is a modified version of example by Jason Mathews, MITRE Corp that was
published on https://commons.apache.org/proper/commons-net/index.html
with the Apache Commons Net software.
 */

import android.util.Log;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.System.exit;

public class NTPClient implements AutoCloseable {

    private static final int NTP_POLL_COUNT = 11;
    private static final int NTP_TIMEOUT = 100;
    private static final String LOG_TAG = "NTPClient";
    private ReadWriteLock lock;

    private InetAddress hostAddr;
    NTPUDPClient ntpUdpClient;

    private long mean_offset;
    private long mean_delay;
    private long offset_err;
    private long delay_err;

    private boolean sync;

    public NTPClient(String host) throws UnknownHostException, SocketException {
        this.hostAddr = InetAddress.getByName(host);
        this.ntpUdpClient = new NTPUDPClient();
        this.ntpUdpClient.setDefaultTimeout(10000);
        this.ntpUdpClient.open();
        this.ntpUdpClient.setSoTimeout(NTP_TIMEOUT);
        this.sync = false;
        this.lock = new ReentrantReadWriteLock();

        this.pollNtpServer();
    }

    public void pollNtpServer() {
        SummaryStatistics offsets = new SummaryStatistics();
        SummaryStatistics delays = new SummaryStatistics();
        try {
            for (int i = 0; i < NTP_POLL_COUNT; i++)
            {
                TimeInfo ti = ntpUdpClient.getTime(hostAddr);
                ti.computeDetails();

                offsets.addValue(ti.getOffset());
                delays.addValue(ti.getDelay());
            }
        } catch (IOException e) {
            e.printStackTrace();
            this.close();
            exit(-1);
        }

        this.lock.writeLock().lock();
        this.mean_offset = Math.round(offsets.getMean());
        this.mean_delay = Math.round(delays.getMean());
        this.offset_err = Math.round(offsets.getStandardDeviation());
        this.delay_err = Math.round(delays.getStandardDeviation());
        this.sync = true;
        this.lock.writeLock().lock();

        Log.i(LOG_TAG, "Polled " + this.hostAddr.toString());
        Log.i(LOG_TAG, "Local time: " + System.currentTimeMillis());
        Log.i(LOG_TAG, "Server time: " + this.currentTimeMillis());
        Log.i(LOG_TAG, String.format(
                "Offset: %f (+- %f) ms\tDelay: %f (+- %f) ms",
                offsets.getMean(),
                offsets.getStandardDeviation(),
                delays.getMax(),
                delays.getStandardDeviation()
        ));
    }

    public long getMeanOffset() {
        this.lock.readLock().lock();
        long result = this.mean_offset;
        this.lock.readLock().unlock();
        return result;
    }

    public long getMeanDelay() {
        this.lock.readLock().lock();
        long result = this.mean_delay;
        this.lock.readLock().unlock();
        return result;
    }

    public long getOffsetError() {
        this.lock.readLock().lock();
        long result = this.offset_err;
        this.lock.readLock().unlock();
        return result;
    }

    public long getDelayError() {
        this.lock.readLock().lock();
        long result = this.delay_err;
        this.lock.readLock().unlock();
        return result;
    }

    /**
     * Returns milliseconds just as System.currentTimeMillis() but using the latest
     * estimate from the remote time server.
     *
     * @return the difference, measured in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     */
    public long currentTimeMillis() {
        this.lock.readLock().lock();
        if (!this.sync) {
            this.lock.readLock().unlock();
            this.pollNtpServer();
            this.lock.readLock().lock();
        }

        //long diff = System.currentTimeMillis() - this.timeInfoSetLocalTime;
        //long result = timeInfo.getMessage().getReceiveTimeStamp().getTime() + diff;
        long result = System.currentTimeMillis() + this.mean_offset;
        this.lock.readLock().unlock();

        return result;
    }

    @Override
    public void close() {
        this.lock.writeLock().lock();
        if (null != ntpUdpClient) {
            ntpUdpClient.close();
            ntpUdpClient = null;
        }
        this.sync = false;
        this.lock.writeLock().unlock();
    }

}