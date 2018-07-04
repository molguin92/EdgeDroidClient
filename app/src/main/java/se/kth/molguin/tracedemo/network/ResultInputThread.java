package se.kth.molguin.tracedemo.network;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;

import se.kth.molguin.tracedemo.network.control.ConnectionManager;
import se.kth.molguin.tracedemo.network.gabriel.ProtocolConst;
import se.kth.molguin.tracedemo.network.gabriel.TokenPool;
import se.kth.molguin.tracedemo.synchronization.NTPClient;

public class ResultInputThread extends SocketInputThread {

    private static final String LOG_TAG = "SocketInputThread";

    private NTPClient ntpClient;
    private TokenPool tokenPool;

    public ResultInputThread(Socket socket, NTPClient ntpClient, TokenPool tokenPool) {
        super(socket);
        this.ntpClient = ntpClient;
        this.tokenPool = tokenPool;
    }

    @Override
    protected int processIncoming(DataInputStream socket_in) throws IOException {
        int total_read = 0;
        double timestamp;

        Log.d(LOG_TAG, "Wait for incoming messages...");

        // get incoming message size:
        int len = socket_in.readInt();
        total_read += 4;

        byte[] msg_b = new byte[len];

        // read the message into a string
        int readSize = 0;
        while (readSize < len) {
            int ret = socket_in.read(msg_b, readSize, len - readSize);
            if (ret <= 0) {
                throw new IOException();
            }
            readSize += ret;
        }
        total_read += len;
        timestamp = this.ntpClient.currentTimeMillis();

        String msg_s = new String(msg_b, "UTF-8");
        Log.d(LOG_TAG, "Got incoming message, size: " + len);

        // parse the string into a JSON
        String status;
        String result;
        long frameID;
        try {
            JSONObject msg = new JSONObject(msg_s);
            status = msg.getString(ProtocolConst.HEADER_MESSAGE_STATUS);
            result = msg.getString(ProtocolConst.HEADER_MESSAGE_RESULT);
            //String sensorType = msg.getString(ProtocolConst.SENSOR_TYPE_KEY);
            frameID = msg.getLong(ProtocolConst.HEADER_MESSAGE_FRAME_ID);
            //String engineID = msg.getString(ProtocolConst.HEADER_MESSAGE_ENGINE_ID);
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Received message is not valid Gabriel message.");
            return total_read;
        }

        VideoFrame rcvd_frame = new VideoFrame((int) frameID, null, timestamp);
        ConnectionManager cm;
        try {
            cm = ConnectionManager.getInstance();
        } catch (ConnectionManager.ConnectionManagerException e) {
            //e.printStackTrace();
            return total_read;
        }

        if (status.equals(ProtocolConst.STATUS_SUCCESS)) {
            // differentiate different types of messages
            try {
                JSONObject result_json = new JSONObject(result);
                int state_index = result_json.getInt("state_index");

                if (state_index >= 0) cm.notifySuccessForFrame(rcvd_frame, state_index);
                else cm.notifyMistakeForFrame(rcvd_frame);

            } catch (JSONException e) {
                Log.w(LOG_TAG, "Received message is not valid Gabriel message.");
            }
        } else
            cm.notifyNoResultForFrame(rcvd_frame);

        // we got a valid message, give back a token
        this.tokenPool.putToken();
        return total_read; // return number of read bytes
    }


}
