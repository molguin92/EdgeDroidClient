package se.kth.molguin.tracedemo.network.control.experiment.run;

import android.util.Log;

import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import se.kth.molguin.tracedemo.network.control.ControlConst;
import se.kth.molguin.tracedemo.synchronization.INTPSync;
import se.kth.molguin.tracedemo.utils.AtomicDouble;

public class RunStats {
    private static final String LOG_TAG = "RunStats";
    private static final int STAT_WINDOW_SZ = 15;
    private static final int DEFAULT_INIT_MAP_SIZE = 5;

    private final Lock lock;

    private final List<Frame> frames;
    private final ConcurrentHashMap<Integer, Double> outgoing_timestamps;
    private SynchronizedDescriptiveStatistics rtt;

    private final AtomicBoolean success;
    private final AtomicDouble init;
    private final AtomicDouble finish;

    private final INTPSync ntp;

    public RunStats(INTPSync ntpSyncer) {
        this.init = new AtomicDouble(-1);
        this.finish = new AtomicDouble(-1);
        this.success = new AtomicBoolean(false);

        this.lock = new ReentrantLock();

        // initial size of 5 is ok since we'll constantly be removing frames as we get back confirmations
        this.outgoing_timestamps = new ConcurrentHashMap<>(DEFAULT_INIT_MAP_SIZE);
        this.frames = Collections.synchronizedList(new LinkedList<Frame>());
        this.rtt = new SynchronizedDescriptiveStatistics(RunStats.STAT_WINDOW_SZ);
        this.ntp = ntpSyncer;
    }

    public void init() {
        // no strictly thread-safe

        if (this.init.get() < 0 && this.finish.get() < 0)
            this.init.set(this.ntp.currentTimeMillis());
    }

    public void finish(boolean success) throws RunStatsException {
        // not strictly thread-safe
        this.checkInitialized();

        if (this.init.get() > 0 && this.finish.get() < 0) {
            this.finish.set(this.ntp.currentTimeMillis());
            this.success.set(success);
        }
    }

    private void checkInitialized() throws RunStatsException {
        if (this.init.get() < 0)
            throw new RunStatsException("Not initialized!");
    }

    public void registerSentFrame(int frame_id) throws RunStatsException {
        this.checkInitialized();
        this.outgoing_timestamps.put(frame_id, this.ntp.currentTimeMillis());
    }

    public void registerReceivedFrame(int frame_id, boolean feedback) throws RunStatsException {
        this.checkInitialized();
        double in_time = this.ntp.currentTimeMillis();
        Double out_time = this.outgoing_timestamps.get(frame_id);

        if (out_time != null) {
            Frame f = new Frame(frame_id, out_time, in_time, feedback);
            this.frames.add(f);
            this.rtt.addValue(f.getRTT());
        } else
            Log.w(LOG_TAG, "Got reply for frame "
                    + frame_id + " but couldn't find it in the list of sent frames!");
    }

    public double getRollingRTT() throws RunStatsException {
        this.checkInitialized();
        return this.rtt.getMean();
    }

    public JSONObject toJSON() throws JSONException, RunStatsException {

        this.lock.lock();
        try {
            this.checkInitialized();
            this.checkFinalized();

            JSONObject repr = new JSONObject();

            repr.put(ControlConst.Stats.FIELD_RUNBEGIN, this.init.get());
            repr.put(ControlConst.Stats.FIELD_RUNEND, this.finish.get());
            repr.put(ControlConst.Stats.FIELD_RUNTIMESTAMPERROR, this.ntp.getOffsetError());
            repr.put(ControlConst.Stats.FIELD_RUNSUCCESS, this.success.get());
            repr.put(ControlConst.Stats.FIELD_RUNNTPOFFSET, this.ntp.getOffset());

            JSONArray json_frames = new JSONArray();
            for (Frame f : this.frames) {
                json_frames.put(f.toJSON());
            }

            repr.put(ControlConst.Stats.FIELD_RUNFRAMELIST, json_frames);

            return repr;
        } finally {
            this.lock.unlock();
        }
    }

    private void checkFinalized() throws RunStatsException {
        if (this.finish.get() < 0)
            throw new RunStatsException("Not finalized!");
    }

    protected boolean succeeded() {
        this.lock.lock();
        try {
            return this.success.get();
        } finally {
            this.lock.unlock();
        }
    }

    private static class Frame {
        final int id;
        final double sent;
        final double recv;
        final boolean feedback;

        Frame(int id, double sent, double recv, boolean feedback) {
            this.id = id;
            this.sent = sent;
            this.recv = recv;
            this.feedback = feedback;
        }

        double getRTT() {
            return this.recv - this.sent;
        }

        JSONObject toJSON() throws JSONException {
            JSONObject repr = new JSONObject();
            repr.put(ControlConst.Stats.FRAMEFIELD_ID, this.id);
            repr.put(ControlConst.Stats.FRAMEFIELD_SENT, this.sent);
            repr.put(ControlConst.Stats.FRAMEFIELD_RECV, this.recv);
            repr.put(ControlConst.Stats.FRAMEFIELD_FEEDBACK, this.feedback);

            return repr;
        }
    }

    public static class RunStatsException extends Exception {
        RunStatsException(String msg) {
            super(msg);
        }
    }
}
