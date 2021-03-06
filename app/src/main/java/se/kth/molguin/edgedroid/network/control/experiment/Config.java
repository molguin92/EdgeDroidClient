/**
 * Copyright 2019 Manuel Olguín
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.kth.molguin.edgedroid.network.control.experiment;

import org.json.JSONException;
import org.json.JSONObject;

import se.kth.molguin.edgedroid.network.control.ControlConst;

public class Config {
    public final String experiment_id;
    public final int client_id;
    //public int runs;
    public final int num_steps;
    // public String trace_url;
    public final String ntp_host;

    public final String server;

    public final int video_port;
    public final int control_port;
    public final int result_port;

    public final int fps;
    public final int rewind_seconds;
    public final int max_replays;

    public Config(JSONObject json) throws JSONException {
        this.experiment_id = json.getString(ControlConst.EXPCONFIG_ID);
        this.client_id = json.getInt(ControlConst.EXPCONFIG_CLIENTIDX);
        //this.runs = json.getInt(Constants.EXPCONFIG_RUNS);
        this.num_steps = json.getInt(ControlConst.EXPCONFIG_STEPS);
        this.fps = json.getInt(ControlConst.EXPCONFIG_FPS);
        this.rewind_seconds = json.getInt(ControlConst.EXPCONFIG_REWIND_SECONDS);
        this.max_replays = json.getInt(ControlConst.EXPCONFIG_MAX_REPLAYS);
        // this.trace_url = json.getString(ControlConst.EXPCONFIG_TRACE);
        this.ntp_host = json.getString(ControlConst.EXPCONFIG_NTP);

        JSONObject ports = json.getJSONObject(ControlConst.EXPCONFIG_PORTS);
        this.video_port = ports.getInt(ControlConst.EXPPORTS_VIDEO);
        this.control_port = ports.getInt(ControlConst.EXPPORTS_CONTROL);
        this.result_port = ports.getInt(ControlConst.EXPPORTS_RESULT);

        this.server = ControlConst.SERVER; // TODO: For now
    }

}
