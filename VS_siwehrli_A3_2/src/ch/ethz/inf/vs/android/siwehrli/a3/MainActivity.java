package ch.ethz.inf.vs.android.siwehrli.a3;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.ToggleButton;

public class MainActivity extends Activity {
	private static final String LOG_TAG = "Chat";
	private static final String HOST_NAME = "vslab.inf.ethz.ch";
	private static final int REGISTER_LOCALHOST_PORT = 4000;
	private static final int REGISTER_SERVER_PORT = 4000;
	private static final int CHAT_LOCALHOST_PORT = 4001;
	private static final int CHAT_SERVER_PORT = 4001;
	private static final int TOAST_DURATION = Toast.LENGTH_SHORT;
	private static final int PACKET_SIZE = 1024;
	private static final int REGISTRATION_TIMEOUT = 10000;
	private static final int MESSAGE_RECEIVE_TIMEOUT = 2000;

	private static final String SETTINGS_NAME = "Settings";
	private MyArrayAdapter adapter;
	private ReceiveTask receiveTask = new ReceiveTask();

	private DatagramSocket messageSocket = null;

	/**
	 * On the handler it's possible to post Runnables to be executed by the GUI
	 * thread
	 */
	private Handler handler;

	// data structure for holding messages
	ArrayList<TextMessage> messages = new ArrayList<TextMessage>();
	PriorityBlockingQueue<TextMessage> messagesPrearrived = new PriorityBlockingQueue<TextMessage>();

	private boolean registered = false;
	private String userName = ""; // is saved if app is stopped by OS
	private int index = 0;
	private Map<Integer, Integer> initialTimeVector = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		ListView listView = (ListView) findViewById(R.id.listViewMessages);
		messages.add(new TextMessage("Hello testmessage", 54));

		// Assign adapter to ListView
		adapter = new MyArrayAdapter(this, messages);
		listView.setAdapter(adapter);

		// read settings into private fields
		SharedPreferences settings = getSharedPreferences(SETTINGS_NAME,
				MODE_PRIVATE);
		this.userName = settings.getString("user_name", "");
		EditText editName = (EditText) findViewById(R.id.editName);
		editName.setText(this.userName);

		try {
			messageSocket = new DatagramSocket(CHAT_LOCALHOST_PORT);
			messageSocket.setSoTimeout(MESSAGE_RECEIVE_TIMEOUT);
		} catch (SocketException e) {
			Log.e(LOG_TAG, e.getMessage());
		}

		// initial handler with this thread (GUI thread!)
		handler = new Handler();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (registered) {
			RegisterTask task = new RegisterTask(userName, false);
			task.execute();

			// save settings
			SharedPreferences settings = getSharedPreferences(SETTINGS_NAME,
					MODE_PRIVATE);
			SharedPreferences.Editor editor = settings.edit();
			editor.putString("user_name", this.userName);
			editor.commit(); // Commit changes to file!!!
		}
		
		this.receiveTask.cancel(false);
	}

	public void onClickRegister(View view) {
		ToggleButton tb = ((ToggleButton) view);

		EditText editName = (EditText) findViewById(R.id.editName);
		this.userName = editName.getText().toString();

		RegisterTask task = new RegisterTask(userName, tb.isChecked());
		task.execute();
	}

	public void onClickSend(View view) {
		if (registered) {
			EditText editMessage = (EditText) findViewById(R.id.editMessage);
			String message = editMessage.getText().toString();
			if (!message.equals("")) {
				SendTask task = new SendTask();
				task.execute(message);
			} else {
				Toast.makeText(this, R.string.message_empty, TOAST_DURATION)
						.show();
			}
		} else {
			Toast.makeText(this, R.string.register_needed, TOAST_DURATION)
					.show();
		}
	}

	private static String createRequest_register(String userName)
			throws JSONException {
		JSONObject object = new JSONObject();

		object.put("cmd", "register");
		object.put("user", userName);

		return object.toString();
	}

	private static String createRequest_deregister(String userName)
			throws JSONException {
		JSONObject object = new JSONObject();

		object.put("cmd", "deregister");

		return object.toString();
	}

	private static String createRequest_info() throws JSONException {
		JSONObject object = new JSONObject();

		object.put("cmd", "info");

		return object.toString();
	}

	private class RegisterTask extends AsyncTask<Void, Void, Boolean> {
		private ProgressDialog progressDialog;
		String userName;
		int toastID = R.string.hello_world;
		private boolean registering;

		public RegisterTask(String userName, boolean register) {
			this.userName = userName;
			this.registering = register;
		}

		@Override
		protected void onPreExecute() {
			if (registering)
				progressDialog = ProgressDialog.show(MainActivity.this, "",
						getResources().getString(R.string.dialog_register));
			else
				progressDialog = ProgressDialog.show(MainActivity.this, "",
						getResources().getString(R.string.dialog_deregister));
		}

		@Override
		/**
		 * returns if user is registered after background operation has completed
		 */
		protected Boolean doInBackground(Void... args) {
			if (registering) {

				if (this.userName.equals("")) {
					this.toastID = R.string.name_empty;
					return false;
				} else {
					if (this.register()) {
						this.toastID = R.string.register_ok;
						return true;
					} else {
						this.toastID = R.string.register_failed;
						return false;
					}
				}

			} else {
				if (this.deregister()) {
					this.toastID = R.string.deregister_ok;
					return false;
				} else {
					this.toastID = R.string.deregister_failed;
					return true;
				}
			}
		}

		/**
		 * returns if user is registered after operation has completed
		 */
		private boolean register() {
			Log.d(LOG_TAG, "Register user with name: " + userName);
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(REGISTER_LOCALHOST_PORT);

				InetAddress to = InetAddress.getByName(HOST_NAME);
				String request = createRequest_register(userName);
				Log.d(LOG_TAG, "Request: " + request);

				byte[] data = request.getBytes();

				DatagramPacket packet = new DatagramPacket(data, data.length,
						to, REGISTER_SERVER_PORT);

				socket.send(packet);

				// Receive
				data = new byte[PACKET_SIZE];
				DatagramPacket pack = new DatagramPacket(data, PACKET_SIZE);
				socket.setSoTimeout(REGISTRATION_TIMEOUT);
				socket.receive(pack);

				String answer = new String(pack.getData(), 0, pack.getLength());
				Log.d(LOG_TAG, "Received message: " + answer);

				JSONObject jsonAnswer = new JSONObject(answer);
				String success = jsonAnswer.getString("success");
				if (success.equals("reg_ok")) {
					index = Integer.parseInt(jsonAnswer.getString("index"));
					initialTimeVector = TextMessage.readTimeVector(jsonAnswer
							.getJSONObject("time_vector"));
					socket.close();
					return true;
				} else {
					socket.close();
					return false;
				}

			} catch (SocketException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (JSONException e) {
				Log.e(LOG_TAG, e.getMessage());
			}

			if (socket != null)
				socket.close();

			return false;
		}

		/**
		 * returns if user is registered after operation has completed
		 */
		private boolean deregister() {
			Log.d(LOG_TAG, "Deregister user with name: " + userName);
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(REGISTER_LOCALHOST_PORT);

				InetAddress to = InetAddress.getByName(HOST_NAME);
				String request = createRequest_deregister(userName);
				Log.d(LOG_TAG, "Request: " + request);

				byte[] data = request.getBytes();

				DatagramPacket packet = new DatagramPacket(data, data.length,
						to, REGISTER_SERVER_PORT);

				socket.send(packet);

				// Receive
				data = new byte[PACKET_SIZE];
				DatagramPacket pack = new DatagramPacket(data, PACKET_SIZE);
				socket.setSoTimeout(REGISTRATION_TIMEOUT);
				socket.receive(pack);

				String answer = new String(pack.getData(), 0, pack.getLength());
				Log.d(LOG_TAG, "Received message: " + answer);

				JSONObject jsonAnswer = new JSONObject(answer);
				String success = jsonAnswer.getString("success");
				if (success.equals("dreg_ok")) {
					socket.close();
					return false;
				} else {
					socket.close();
					return true;
				}

			} catch (SocketException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (JSONException e) {
				Log.e(LOG_TAG, e.getMessage());
			}

			if (socket != null)
				socket.close();

			return true;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			registered = result;

			EditText editName = (EditText) findViewById(R.id.editName);
			editName.setEnabled(!result);

			ToggleButton tb = (ToggleButton) findViewById(R.id.toggleButtonRegister);
			tb.setChecked(result);

			Toast.makeText(getApplicationContext(), this.toastID,
					TOAST_DURATION).show();

			// start listening for messages
			if (result) {
				if(receiveTask.getStatus()==AsyncTask.Status.PENDING)
				receiveTask.execute();
				else if(receiveTask.getStatus()==AsyncTask.Status.FINISHED){
					receiveTask=new ReceiveTask();
					receiveTask.execute();
				}
			}
			else
			{
				receiveTask.cancel(false);
				receiveTask=new ReceiveTask(); // make ready for next time
			}

			progressDialog.dismiss();

		}
	}

	private class SendTask extends AsyncTask<String, Void, Boolean> {
		private boolean ok = false;

		public boolean isSent() {
			return ok;
		}

		@Override
		/**
		 * return if message successfully sent
		 */
		protected Boolean doInBackground(String... args) {
			Log.d(LOG_TAG, "Send message: " + args[0]);

			// Time logic // TODO Frederik edit here
			TextMessage message = new TextMessage(args[0], initialTimeVector); // time
																				// logic
																				// edit
																				// here!

			// sending message
			try {
				InetAddress to = InetAddress.getByName(HOST_NAME);

				// build JSON
				JSONObject jsonMessage = message.getJSONObject();
				jsonMessage.put("cmd", "message");
				String request = jsonMessage.toString();
				Log.d(LOG_TAG, "Sending: " + request);

				// send packet
				byte[] data = request.getBytes();
				DatagramPacket packet = new DatagramPacket(data, data.length,
						to, CHAT_SERVER_PORT);
				messageSocket.send(packet);

				// only add message to view if sent to the server successful
				messages.add(message);
				return true;
			} catch (SocketException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
			} catch (JSONException e) {
				Log.e(LOG_TAG, e.getMessage());
			}

			return false;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (!result)
				Toast.makeText(MainActivity.this,
						R.string.message_sending_failed, TOAST_DURATION).show();
			else {
				adapter.notifyDataSetChanged();
				EditText editMessage = (EditText) findViewById(R.id.editMessage);
				editMessage.setText("");
			}
			this.ok = result;

		}
	}

	private class ReceiveTask extends AsyncTask<Void, Void, Void> {
		@Override
		protected Void doInBackground(Void... args) {
			Log.d(LOG_TAG, "Start receiving messages");

			// receiving messages
			if (messageSocket != null) {
				// Receive
				byte[] data;
				DatagramPacket pack;
				while (!this.isCancelled()) {
					try {
						data = new byte[PACKET_SIZE];
						pack = new DatagramPacket(data, PACKET_SIZE);
						messageSocket.receive(pack);

						String answer = new String(pack.getData(), 0,
								pack.getLength());
						Log.d(LOG_TAG, "Received message: " + answer);

						// parse
						TextMessage message = new TextMessage(new JSONObject(
								answer), initialTimeVector);
						messages.add(message);

					} catch (SocketException e) {
						Log.e(LOG_TAG, e.getMessage());
					} catch (IOException e) {
						Log.e(LOG_TAG, e.getMessage());
					} catch (JSONException e) {
						Log.e(LOG_TAG, e.getMessage());
					}
				}
			}

			messageSocket.close();
			
			Log.d(LOG_TAG, "Stop receiving messages");
			return null;
		}
		
		@Override
		protected void onPostExecute(Void result) {
			adapter.notifyDataSetChanged();
		}
	}
}
