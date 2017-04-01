package edu.ucla.cs.chronochat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.app.NotificationCompat;
import android.text.Editable;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;

import edu.ucla.cs.chronochat.ChronoSyncService.ErrorCode;
import edu.ucla.cs.chronochat.ChatbufProto.ChatMessage.ChatMessageType;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity",
                                SAVED_USERNAME = TAG + ".username",
                                SAVED_CHATROOM = TAG + ".chatroom",
                                SAVED_PREFIX = TAG + ".prefix",
                                SAVED_HUB = TAG + ".hub",
                                SAVED_MESSAGES = TAG + ".messages";

    // index of username component in data names
    private static final int NOTIFICATION_ID = 0;
    public static final int SERVICE_NOTIFICATION_ID = 1;

    private EditText editMessage;
    private ArrayList<ChronoChatMessage> messageList = new ArrayList<>();
    private MessagesAdapter messageListAdapter;

    private String username, chatroom, prefix, hub;

    private boolean activityVisible = false;

    private class LocalBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "broadcast received");
            switch (intent.getAction()) {
                case ChronoChatService.BCAST_RECEIVED_MSG:
                    handleReceivedMessage(intent);
                    break;
                case ChronoSyncService.BCAST_ERROR:
                    handleError(intent);
                    break;
                case ChronoChatService.BCAST_ROSTER:
                    String[] roster = intent.getStringArrayExtra(ChronoChatService.EXTRA_ROSTER);
                    showRoster(roster);
            }
        }
    }

    private LocalBroadcastReceiver broadcastReceiver;

    private void registerBroadcastReceiver() {
        broadcastReceiver = new LocalBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ChronoSyncService.BCAST_ERROR);
        intentFilter.addAction(ChronoChatService.BCAST_RECEIVED_MSG);
        intentFilter.addAction(ChronoChatService.BCAST_ROSTER);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                broadcastReceiver,
                intentFilter);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate");

        editMessage = (EditText) findViewById(R.id.edit_message);
        ListView messageView = (ListView) findViewById(R.id.message_view);

        registerBroadcastReceiver();

        messageListAdapter = new MessagesAdapter(this, messageList);
        messageView.setAdapter(messageListAdapter);

        if (savedInstanceState != null) {
            Log.d(TAG, "restoring saved instance state");
            setUsername(savedInstanceState.getString(SAVED_USERNAME));
            prefix = savedInstanceState.getString(SAVED_PREFIX);
            setChatroom(savedInstanceState.getString(SAVED_CHATROOM));
            hub = savedInstanceState.getString(SAVED_HUB);
            ArrayList<ChronoChatMessage> savedMessages =
                    savedInstanceState.getParcelableArrayList(SAVED_MESSAGES);
            messageListAdapter.addAll(savedMessages);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        activityVisible = true;
        hideNotification();
        if (!loginInfoIsSet())
            getLoginInfo();
    }

    @Override
    public void onStop() {
        super.onStop();
        activityVisible = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
    }

    @Override
    public void onBackPressed() {
        // don't destroy the activity when "back" is pressed, just hide the application
        moveTaskToBack(true);
    }

    private void getLoginInfo() {
        clearLoginInfo();
        startActivityForResult(new Intent(this, LoginActivity.class), 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK) return;

        messageList.clear();
        messageListAdapter.notifyDataSetChanged();
        setUsername(data.getStringExtra(ChronoChatService.EXTRA_USERNAME));
        prefix = data.getStringExtra(ChronoChatService.EXTRA_PREFIX);
        setChatroom(data.getStringExtra(ChronoChatService.EXTRA_CHATROOM));
        hub = data.getStringExtra(ChronoChatService.EXTRA_HUB);

        ChronoChatMessage join = new ChronoChatMessage(username, chatroom, ChatMessageType.JOIN);
        sendMessage(join);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onSaveInstanceState(Bundle savedState) {
        savedState.putString(SAVED_USERNAME, username);
        savedState.putString(SAVED_CHATROOM, chatroom);
        savedState.putString(SAVED_PREFIX, prefix);
        savedState.putString(SAVED_HUB, hub);
        savedState.putParcelableArrayList(SAVED_MESSAGES, messageList);
        super.onSaveInstanceState(savedState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_leave:
                leaveChatroom();
                return true;
            case R.id.action_show_roster:
                requestRoster();
                return true;
            case R.id.action_quit:
                quitApplication();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void setUsername(String username) {
        this.username = username;
        messageListAdapter.setLoggedInUsername(username);
    }

    private void setChatroom(String chatroom) {
        this.chatroom = chatroom;
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null)
            Log.e(TAG, "couldn't retrieve action bar to set title");
        else {
            actionBar.setTitle(chatroom);
        }
    }

    public void sendMessage(View view) {
        Editable messageField = editMessage.getText();
        String text = messageField.toString();
        if (text.equals("")) return;
        messageField.clear();

        ChronoChatMessage message = new ChronoChatMessage(username, chatroom, ChatMessageType.CHAT,
                text);
        sendMessage(message);
    }

    private void sendMessage(ChronoChatMessage message) {
        messageListAdapter.addMessageToView(message);
        Intent intent = new Intent(this, ChronoChatService.class);
        intent.setAction(ChronoChatService.ACTION_SEND)
              .putExtra(ChronoChatService.EXTRA_MESSAGE, message.toByteArray())
              .putExtra(ChronoChatService.EXTRA_PREFIX, prefix)
              .putExtra(ChronoChatService.EXTRA_HUB, hub);
        startService(intent);
    }

    private void handleReceivedMessage(Intent intent) {
        byte[] encodedMessage = intent.getByteArrayExtra(ChronoChatService.EXTRA_MESSAGE);
        ChronoChatMessage message = new ChronoChatMessage(encodedMessage);
        Log.d(TAG, "received message from " + message.getFrom());
        showNotification(message);
        messageListAdapter.addMessageToView(message);
    }


    private void showNotification(ChronoChatMessage message) {

        if (activityVisible) return;

        ChatMessageType type = message.getType();
        if (type != ChatMessageType.CHAT) return;

        String from = message.getFrom(), text = message.getData();

        NotificationCompat.Builder builder =
                (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.notification_icon)
                        .setContentTitle(from)
                        .setContentText(text)
                        .setDefaults(Notification.DEFAULT_SOUND|Notification.DEFAULT_LIGHTS);

        // FIXME is this done right?
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(getApplicationContext(),
                (int)System.currentTimeMillis(), intent, 0);

        builder.setContentIntent(resultPendingIntent);
        builder.setAutoCancel(true);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void hideNotification() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void requestRoster() {
        Intent request = new Intent(this, ChronoChatService.class);
        request.setAction(ChronoChatService.ACTION_GET_ROSTER);
        startService(request);
    }

    private void showRoster(String[] roster) {
        Bundle args = new Bundle();
        args.putStringArray(ChronoChatService.EXTRA_ROSTER, roster);
        RosterDialogFragment dialog = new RosterDialogFragment();
        dialog.setArguments(args);
        dialog.show(getFragmentManager(), "RosterDialogFragment");
    }

    private void handleError(Intent intent) {
        ErrorCode errorCode =
                (ErrorCode) intent.getSerializableExtra(ChronoSyncService.EXTRA_ERROR_CODE);
        String toastText = "";
        switch (errorCode) {
            case NFD_PROBLEM:
                toastText = getString(R.string.error_nfd);
                break;
            case OTHER_EXCEPTION:
                toastText = getString(R.string.error_other);
                break;
        }
        Toast.makeText(getApplicationContext(), toastText, Toast.LENGTH_LONG).show();

        clearLoginInfo();
        if (activityVisible)
            getLoginInfo();
    }

    private void leaveChatroom() {
        ChronoChatMessage leave = new ChronoChatMessage(username, chatroom, ChatMessageType.LEAVE);
        sendMessage(leave);
        getLoginInfo();
    }

    private void quitApplication() {
        Intent request = new Intent(this, ChronoChatService.class);
        request.setAction(ChronoChatService.ACTION_STOP);
        startService(request);
        finish();
    }

    private boolean loginInfoIsSet() {
        return (username != null && chatroom != null && prefix != null && hub != null);
    }

    private void clearLoginInfo() {
        username = chatroom = prefix = hub = null;
    }
}

