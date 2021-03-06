/*
 * Copyright (C) 2012 - 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.runnerup.export;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.runnerup.export.format.TCX;
import org.runnerup.util.Constants.DB;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.util.Pair;

import android.os.Build;
import android.annotation.TargetApi;

@TargetApi(Build.VERSION_CODES.FROYO)
public class GarminUploader extends FormCrawler implements Uploader {

	public static final String NAME = "Garmin";
	public static String START_URL = "https://connect.garmin.com/signin";
	public static String LOGIN_URL = "https://connect.garmin.com/signin";
	public static String CHECK_URL = "http://connect.garmin.com/user/username";
	public static String UPLOAD_URL = "http://connect.garmin.com/proxy/upload-service-1.1/json/upload/.tcx";
	public static String LIST_WORKOUTS_URL = "http://connect.garmin.com/proxy/workout-service-1.0/json/workoutlist";
	public static String GET_WORKOUT_URL = "http://connect.garmin.com/proxy/workout-service-1.0/json/workout/";
	
	long id = 0;
	private String username = null;
	private String password = null;
	private boolean isConnected = false;

	GarminUploader(UploadManager uploadManager) {
	}

	@Override
	public long getId() {
		return id;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void init(ContentValues config) {
		id = config.getAsLong("_id");
		String authToken = config.getAsString(DB.ACCOUNT.AUTH_CONFIG);
		if (authToken != null) {
			try {
				JSONObject tmp = new JSONObject(authToken);
				username = tmp.optString("username", null);
				password = tmp.optString("password", null);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public boolean isConfigured() {
		if (username != null && password != null)
			return true;
		return false;
	}

	@Override
	public String getAuthConfig() {
		JSONObject tmp = new JSONObject();
		try {
			tmp.put("username", username);
			tmp.put("password",  password);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		
		return tmp.toString();
	}

	@Override
	public void reset() {
		username = null;
		password = null;
		isConnected = false;
	}

	@Override
	public Status connect() {
		Status s = Status.NEED_AUTH;
		s.authMethod = Uploader.AuthMethod.USER_PASS;
		if (username == null || password == null) {
			return s;
		}

		if (isConnected) {
			return Status.OK;
		}
		
		Exception ex = null;
		HttpURLConnection conn = null;
		logout();

		try {

			/**
			 * connect to START_URL to get cookies
			 */
			conn = (HttpURLConnection) new URL(START_URL).openConnection();
			{
				int responseCode = conn.getResponseCode();
				String amsg = conn.getResponseMessage();
				getCookies(conn);
				if (responseCode != 200) {
					System.err.println("GarminUploader::connect() - got " + responseCode + ", msg: " + amsg);
				}
			}
			conn.disconnect();

			/**
			 * Then login using a post
			 */
			String login = LOGIN_URL;
			FormValues kv = new FormValues();
			kv.put("login", "login");
			kv.put("login:loginUsernameField", username);
			kv.put("login:password", password);
			kv.put("login:signInButton", "Sign In");
			kv.put("javax.faces.ViewState", "j_id1");
			
			conn = (HttpURLConnection) new URL(login).openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.addRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			addCookies(conn);

			{
				OutputStream wr = new BufferedOutputStream(conn.getOutputStream());
				kv.write(wr);
				wr.flush();
				wr.close();
				int responseCode = conn.getResponseCode();
				String amsg = conn.getResponseMessage();
				System.err.println("code: " + responseCode + ", msg=" + amsg);
				getCookies(conn);
			}
			conn.disconnect();

			/**
			 * An finally check that all is OK
			 */
			conn = (HttpURLConnection) new URL(CHECK_URL).openConnection();
			addCookies(conn);
			{
				conn.connect();
				getCookies(conn);
				InputStream in = new BufferedInputStream(conn.getInputStream());
				JSONObject obj = new JSONObject(new Scanner(in).useDelimiter("\\A").next());
				conn.disconnect();
				int responseCode = conn.getResponseCode();
				String amsg = conn.getResponseMessage();
				// Returns username(which is actually Displayname from profile) if logged in
				if (obj.optString("username", "").length() > 0) {
					isConnected = true;
					return Uploader.Status.OK;
				} else {
					System.err.println("GarminUploader::connect() missing username, obj: " + obj.toString() + ", code: " + responseCode + ", msg: " + amsg);
				}
				return s;
			}
		} catch (MalformedURLException e) {
			ex = e;
		} catch (IOException e) {
			ex = e;
		} catch (JSONException e) {
			ex = e;
		}

		if (conn != null)
			conn.disconnect();

		s = Uploader.Status.ERROR;
		s.ex = ex;
		if (ex != null) {
			ex.printStackTrace();
		}
		return s;
	}

	@Override
	public Status upload(SQLiteDatabase db, long mID) {
		Status s;
		if ((s = connect()) != Status.OK) {
			return s;
		}
		
		TCX tcx = new TCX(db);
		HttpURLConnection conn = null;
		Exception ex = null;
		try {
			StringWriter writer = new StringWriter();
			tcx.export(mID, writer);
			conn = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			addCookies(conn);
			Part<StringWritable> part1 = new Part<StringWritable>("responseContentType",
					new StringWritable(FormCrawler.URLEncode("text/html")));
			Part<StringWritable> part2 = new Part<StringWritable>("data",
					new StringWritable(writer.toString()));
			part2.filename = "RunnerUp.tcx";
			part2.contentType = "application/octet-stream";
			Part<?> parts[] = { part1, part2 };
			postMulti(conn, parts);
			int responseCode = conn.getResponseCode();
			String amsg = conn.getResponseMessage();
			if (responseCode == 200) {
				conn.disconnect();
				return Status.OK;
			}
			ex = new Exception(amsg);
		} catch (IOException e) {
			ex = e;
		}

		s = Uploader.Status.ERROR;
		s.ex = ex;
		if (ex != null) {
			ex.printStackTrace();
		}
		return s;
	}
	@Override
	public boolean checkSupport(Uploader.Feature f) {
		switch(f) {
		case WORKOUT_LIST:
		case GET_WORKOUT:
		case UPLOAD:
			return true;
		case FEED:
		case LIVE:
			break;
		}
		return false;
	}

	@Override
	public Status listWorkouts(List<Pair<String, String>> list) {
		Status s;
		if ((s = connect()) != Status.OK) {
			return s;
		}

		HttpURLConnection conn = null;
		Exception ex = null;
		try {
			conn = (HttpURLConnection) new URL(LIST_WORKOUTS_URL).openConnection();
			conn.setRequestMethod("GET");
			addCookies(conn);
			conn.connect();
			getCookies(conn);
			InputStream in = new BufferedInputStream(conn.getInputStream());
			JSONObject obj = new JSONObject(new Scanner(in).useDelimiter("\\A").next());
			conn.disconnect();
			int responseCode = conn.getResponseCode();
			String amsg = conn.getResponseMessage();
			if (responseCode == 200) {
				obj = obj.getJSONObject("com.garmin.connect.workout.dto.BaseUserWorkoutListDto");
				JSONArray arr = obj.getJSONArray("baseUserWorkouts");
				for (int i = 0; ; i++) {
					obj = arr.optJSONObject(i);
					if (obj == null)
						break;
					list.add(new Pair<String,String>(obj.getString("workoutId"), obj.getString("workoutName") + ".json"));
				}
				return Status.OK;
			}
			ex = new Exception(amsg);
		} catch (IOException e) {
			ex = e;
		} catch (JSONException e) {
			ex = e;
		}

		s = Uploader.Status.ERROR;
		s.ex = ex;
		if (ex != null) {
			ex.printStackTrace();
		}
		return s;
	}

	@Override
	public void downloadWorkout(File dst, String key) throws Exception {
		HttpURLConnection conn = null;
		Exception ex = null;
		FileOutputStream out = null;
		try {
			conn = (HttpURLConnection) new URL(GET_WORKOUT_URL + key).openConnection();
			conn.setRequestMethod("GET");
			addCookies(conn);
			conn.connect();
			getCookies(conn);
			InputStream in = new BufferedInputStream(conn.getInputStream());
			out = new FileOutputStream(dst);
			int cnt = 0;
			byte buf[] = new byte[1024];
			while (in.read(buf) > 0) {
				cnt += buf.length;
				out.write(buf);
			}
			System.err.println("downloaded workout key: " + key + " " + cnt + " bytes from " + getName());
			in.close();
			out.close();
			conn.disconnect();
			int responseCode = conn.getResponseCode();
			String amsg = conn.getResponseMessage();
			if (responseCode == 200) {
				return;
			}
			ex = new Exception(amsg);
		} catch (Exception e) {
			ex = e;
		}

		if (conn != null) {
			try {
				conn.disconnect();
			} catch (Exception e) {
			}
		}
		
		if (out != null) {
			try {
				out.close();
			} catch (Exception e) {
			}
		}
		ex.printStackTrace();
		throw ex;
	}
	
	@Override
	public void logout() {
		super.logout();
	}
};
