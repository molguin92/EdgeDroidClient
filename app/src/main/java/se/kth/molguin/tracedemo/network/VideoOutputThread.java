package se.kth.molguin.tracedemo.network;

import android.content.Context;
import android.util.Log;

//import com.instacart.library.truetime.TrueTimeRx;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import se.kth.molguin.tracedemo.network.control.ControlConst;
import se.kth.molguin.tracedemo.network.gabriel.ConnectionManager;
import se.kth.molguin.tracedemo.network.gabriel.ProtocolConst;
import se.kth.molguin.tracedemo.network.gabriel.TokenPool;
import se.kth.molguin.tracedemo.synchronization.NTPClient;
import se.kth.molguin.tracedemo.task.TaskStep;

import static java.lang.System.exit;

public class VideoOutputThread implements Runnable {

    private final static String LOG_TAG = "VideoOutput";

    private Timer timer;

    private ReentrantLock new_frame_lock;
    private Condition new_frame_cond;

    private ReentrantLock running_lock;
    private ReentrantLock loading_lock;

    private byte[] next_frame;
    private boolean running;
    private int frame_counter;
    private int current_step_idx;
    private int num_steps;
    private TaskStep previous_step;
    private Context app_context;
    private NTPClient ntpClient;

    private DataOutputStream socket_out;

    public int getCurrentStepIndex() {
        this.running_lock.lock();
        try {
            return current_step_idx;
        } finally {
            this.running_lock.unlock();
        }

//        synchronized (run_lock) {
//            return current_step_idx;
//        }
    }

    private TaskStep current_step;
    private TaskStep next_step;
    //private ContentResolver contentResolver;

    private boolean task_success;

    private int fps;
    private int rewind_seconds;
    private int max_replays;

    public VideoOutputThread(Socket socket, int num_steps, int fps, int rewind_seconds,
                             int max_replays, Context app_context, NTPClient ntpClient)
            throws IOException {
        this.frame_counter = 0;
        this.socket_out = new DataOutputStream(socket.getOutputStream());

        this.fps = fps;
        this.rewind_seconds = rewind_seconds;
        this.max_replays = max_replays;

        this.current_step_idx = 0;
        this.num_steps = num_steps;
        this.app_context = app_context;

        this.current_step = null;
        this.next_step = null;
        this.previous_step = null;
        this.timer = new Timer();
        this.task_success = false;

        this.ntpClient = ntpClient;

        this.new_frame_lock = new ReentrantLock();
        this.new_frame_cond = this.new_frame_lock.newCondition();

        this.running_lock = new ReentrantLock();
        this.loading_lock = new ReentrantLock();

        try {
            this.goToStep(this.current_step_idx);
        } catch (VideoOutputThreadException e) {
            e.printStackTrace();
            exit(-1);
        }
    }

    public void goToStep(final int step_idx) throws VideoOutputThreadException {
        Log.i("VideoOutputThread", "Moving to step " + step_idx + " from step " + this.current_step_idx);

        this.running_lock.lock();
        try {
//        synchronized (run_lock) {
            if (step_idx == this.num_steps) {
                // done with the task, finish
                if (this.current_step != null)
                    this.current_step.stop();

                Log.i("VideoOutputThread", "Success!");
                this.task_success = true;
                this.finish();
                return;
            } else if (step_idx < 0 || step_idx > this.num_steps)
                throw new VideoOutputThreadException(EXCEPTIONSTATE.INVALIDSTEPINDEX);


            if (this.current_step_idx != step_idx) {
                if (this.current_step != null)
                    this.current_step.stop();

                this.loading_lock.lock();
                try {
                    if (this.current_step_idx + 1 == step_idx) {
                        //Log.i(LOG_TAG, "New step is next step.");
                        this.current_step = this.next_step;

                        // prepare next and previous
                        this.next_step = null;
                        if (this.previous_step != null) this.previous_step.stop();
                        this.previous_step = null;

                    } else if (this.current_step_idx - 1 == step_idx) {
                        //Log.i(LOG_TAG, "New step is previous step.");

                        this.current_step = this.previous_step;

                        this.previous_step = null;
                        if (this.next_step != null) this.next_step.stop();
                        this.next_step = null;
                    } else {
                        //Log.i(LOG_TAG, "New step is other step.");
                        this.current_step = new TaskStep(
                                this.getDataInputStreamForStep(step_idx),
                                this, this.fps, this.rewind_seconds, this.max_replays);

                        if (this.next_step != null) this.next_step.stop();
                        if (this.previous_step != null) this.previous_step.stop();
                        this.next_step = null;
                        this.previous_step = null;

                    }
                } finally {
                    this.loading_lock.unlock();
                }

                this.current_step_idx = step_idx;

            } else if (this.current_step == null) {
                this.current_step = new TaskStep(
                        this.getDataInputStreamForStep(this.current_step_idx),
                        this, this.fps, this.rewind_seconds, this.max_replays);
            }
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "Exception!", e);
            exit(-1);
        } finally {
            this.running_lock.unlock();
        }

        this.preLoadSteps();

        if (this.running) {
            //Log.i(LOG_TAG, "Starting new step.");
            this.current_step.start();
        }
    }

    private void preLoadSteps() {
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                VideoOutputThread.this.loading_lock.lock();
                try {

                    final int current_step_idx = VideoOutputThread.this.current_step_idx;
                    final int next_step_idx = current_step_idx + 1;
                    final int previous_step_idx = current_step_idx - 1;
                    final int num_steps = VideoOutputThread.this.getNumSteps();

                    TaskStep next_step = VideoOutputThread.this.next_step;
                    TaskStep previous_step = VideoOutputThread.this.previous_step;

                    if (next_step != null && next_step.getStepIndex() != next_step_idx) {
                        next_step.stop();
                        next_step = null;
                    }

                    if (next_step == null && next_step_idx < num_steps)
                        VideoOutputThread.this.next_step =
                                new TaskStep(VideoOutputThread.this.getDataInputStreamForStep(next_step_idx),
                                        VideoOutputThread.this,
                                        VideoOutputThread.this.fps,
                                        VideoOutputThread.this.rewind_seconds,
                                        VideoOutputThread.this.max_replays);

                    if (previous_step != null && previous_step.getStepIndex() != previous_step_idx) {
                        previous_step.stop();
                        previous_step = null;
                    }

                    if (previous_step == null && previous_step_idx >= 0)
                        VideoOutputThread.this.previous_step =
                                new TaskStep(VideoOutputThread.this.getDataInputStreamForStep(previous_step_idx),
                                        VideoOutputThread.this,
                                        VideoOutputThread.this.fps,
                                        VideoOutputThread.this.rewind_seconds,
                                        VideoOutputThread.this.max_replays
                                );

                } catch (FileNotFoundException e) {
                    Log.e(VideoOutputThread.LOG_TAG, "Exception!", e);
                    exit(-1);
                } finally {
                    VideoOutputThread.this.loading_lock.unlock();
                }
            }
        }, 0);
    }

    public void finish() {
        this.running_lock.lock();
        try {
//        synchronized (run_lock) {
            this.running = false;
            if (this.current_step != null)
                this.current_step.stop();
        } finally {
            this.running_lock.unlock();
        }

        this.new_frame_lock.lock();
        try {
            this.new_frame_cond.signalAll();
        } finally {
            this.new_frame_lock.unlock();
        }

//        synchronized (frame_lock) {
//            frame_lock.notifyAll();
//        }

        TokenPool.getInstance().putToken(); // in case the system is waiting for a token

        if (this.next_step != null) this.next_step.stop();
        if (this.previous_step != null) this.previous_step.stop();

        this.current_step_idx = -1;
        this.current_step = null;
        this.next_step = null;
        this.previous_step = null;
    }

    private DataInputStream getDataInputStreamForStep(int index) throws FileNotFoundException {
        this.loading_lock.lock();
        try {
            return new DataInputStream(this.app_context.openFileInput(
                    ControlConst.STEP_PREFIX + (index + 1) + ControlConst.STEP_SUFFIX
            ));
        } finally {
            this.loading_lock.unlock();
        }
    }

    private int getNumSteps() {
        return this.num_steps;
    }

    public void pushFrame(byte[] frame) {

        this.new_frame_lock.lock();
        try {
            this.next_frame = frame;
            ConnectionManager.getInstance().notifyPushFrame(frame);
            this.new_frame_cond.signalAll();
        } catch (ConnectionManager.ConnectionManagerException e) {
            Log.e(LOG_TAG, "Exception!", e);
            exit(-1);
        } finally {
            this.new_frame_lock.unlock();
        }

//        synchronized (frame_lock) {
//            this.next_frame = frame;
//            try {
//                ConnectionManager.getInstance().notifyPushFrame(frame);
//            } catch (ConnectionManager.ConnectionManagerException e) {
//                Log.e(LOG_TAG, "Exception!", e);
//                exit(-1);
//            }
//            frame_lock.notifyAll();
//        }
    }

    @Override
    public void run() {

        this.running_lock.lock();
        try {
            this.running = true;
            this.current_step.start();
        } finally {
            this.running_lock.unlock();
        }

        TokenPool tk = TokenPool.getInstance();

        byte[] frame_to_send;
        int frame_id;

        while (this.isRunning()) {

            // first, need to get a token
            try {
                tk.getToken();
            } catch (InterruptedException e) {
                break;
            }

            // now we have a token and can try to send stuff
            this.new_frame_lock.lock();
            try {
                while (this.next_frame == null) {
                    // re-check that we're actually running
                    // wait can hang for a long while, so we need to do this
                    this.running_lock.lock();
                    try {
                        if (!this.running) break;
                    } finally {
                        this.running_lock.unlock();
                    }
                    this.new_frame_cond.await();
                }

                this.frame_counter += 1;
                frame_id = this.frame_counter;
                frame_to_send = this.next_frame;
                this.next_frame = null;
            } catch (InterruptedException e) {
                break;
            } finally {
                this.new_frame_lock.unlock();
            }

            this.running_lock.lock();
            try {
                if (!this.running || frame_to_send == null) break;
            } finally {
                this.running_lock.unlock();
            }

            try {
                this.sendFrame(frame_id, frame_to_send);
            } catch (ConnectionManager.ConnectionManagerException e) {
                Log.e(LOG_TAG, "Exception!", e);
                exit(-1);
            }

        }

        this.running_lock.lock();
        try {
            if (this.running) this.finish();
        } finally {
            this.running_lock.unlock();
        }

        try {
            ConnectionManager.getInstance().notifyEndStream(this.task_success);
        } catch (ConnectionManager.ConnectionManagerException ignored) {
        }
    }

    private boolean isRunning()
    {
        this.running_lock.lock();
        try {
            return this.running;
        }
        finally {
            this.running_lock.unlock();
        }
    }

    private void sendFrame(int id, byte[] data) throws ConnectionManager.ConnectionManagerException {
        byte[] header = String.format(Locale.ENGLISH, ProtocolConst.VIDEO_HEADER_FMT, id).getBytes();

        try (// use auxiliary output streams to write everything out at once
             ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream daos = new DataOutputStream(baos)
        ) {
            daos.writeInt(header.length);
            daos.write(header);
            daos.writeInt(data.length);
            daos.write(data);

            byte[] out_data = baos.toByteArray();
            this.socket_out.write(out_data); // send!
            this.socket_out.flush();

            ConnectionManager.getInstance().notifySentFrame(
                    new VideoFrame(id, data, this.ntpClient.currentTimeMillis())
            );
        } catch (IOException e) {
            Log.e(LOG_TAG, "IOException while sending data:", e);
            exit(-1);
        }
    }

    private enum EXCEPTIONSTATE {
        NULLCONTENTRESOLVER,
        INVALIDSTEPINDEX
    }

    public static class VideoOutputThreadException extends Exception {
        String msg;

        VideoOutputThreadException(EXCEPTIONSTATE state) {
            super();

            switch (state) {
                case NULLCONTENTRESOLVER:
                    this.msg = "ContentResolver is null!";
                    break;
                case INVALIDSTEPINDEX:
                    this.msg = "Invalid step index!";
                    break;
                default:
                    this.msg = "???";
                    break;
            }
        }

        @Override
        public String getMessage() {
            return super.getMessage() + ": " + this.msg;
        }
    }
}
