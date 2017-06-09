/*
 * Created by Itzik Braun on 12/3/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 3/12/15 4:27 PM
 */

package co.chatsdk.ui.chat;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ListView;
import android.widget.ProgressBar;

import co.chatsdk.core.NM;

import co.chatsdk.core.dao.BMessage;
import co.chatsdk.core.dao.BMessageDao;
import co.chatsdk.core.dao.BThread;
import co.chatsdk.core.types.Defines;
import co.chatsdk.core.types.ImageUploadResult;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import wanderingdevelopment.tk.sdkbaseui.R;

import wanderingdevelopment.tk.sdkbaseui.UiHelpers.ChatSDKUiHelper;

import co.chatsdk.core.dao.sorter.MessageSorter;

import co.chatsdk.core.dao.DaoCore;
import com.braunster.chatsdk.object.ChatSDKThreadPool;
import com.braunster.chatsdk.object.Cropper;
import com.github.johnpersano.supertoasts.SuperCardToast;

import org.apache.commons.lang3.StringUtils;
import org.greenrobot.greendao.query.QueryBuilder;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

public class ChatHelper {

    public static final int ERROR = 1991, NOT_HANDLED = 1992, HANDLED = 1993;

    /** The key to get the shared file uri. This is used when the activity is opened to share and image or a file with the chat users.
     *  Example can be found in ContactsFragment that use click mode share with contact. */
    public static final String SHARED_FILE_URI = "share_file_uri";

    /** The key to get shared text, this is used when the activity is open to share text with the chat user.
     *  Example can be found in ContactsFragment that use click mode share with contact. */
    public static final String SHARED_TEXT = "shared_text";

    public static final String READ_COUNT = "read_count";

    public static final String FILE_NAME = "file_name";
    
    /** The key to get the path of the last captured image path in case the activity is destroyed while capturing.*/
    public static final String SELECTED_FILE_PATH = "captured_photo_path";

    private Cropper crop;
    
    protected static final int PHOTO_PICKER_ID = 100;
    protected static final int CAPTURE_IMAGE = 101;
    public static final int PICK_LOCATION = 102;

    public static final int ADD_USERS = 103;

    /** The amount of messages that was loaded for this thread,
     *  When we load more then the default messages amount we want to keep the amount so we could load them again if the list needs to be re-created.*/
    public static final String LOADED_MESSAGES_AMOUNT = "LoadedMessagesAmount";
    private int loadedMessagesAmount = 0;

    /** The selected file that is picked to be sent.
     *  This is also the path to the camera output.*/
    private String selectedFilePath = "";

    /**
     * The file name of the image that was picked and cropped
     **/
    private String mFileName;

    /** Keeping track of the amount of messages that was read in this thread.*/
    private int readCount = 0;

    private static final String TAG = ChatHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private WeakReference<AppCompatActivity> activity;
    private BThread thread;
    private ChatSDKUiHelper uiHelper;
    private ListView listMessages;
    private ProgressBar progressBar;
    private MessagesListAdapter messagesListAdapter;

    public ChatHelper(AppCompatActivity activity, BThread thread, ChatSDKUiHelper uiHelper) {
        this.activity = new WeakReference<>(activity);
        this.thread = thread;
        this.uiHelper = uiHelper;
    }

    /** Load messages from the database and saving the current position of the list.*/
    public void loadMessagesAndRetainCurrentPos(){
        loadMessages(true, false, 0, 0);
    }

    // TODO: Refactor this
    public void loadMessages(final boolean retain, final boolean hideListView, final int offsetOrPos, final int amountToLoad){

        if (messagesListAdapter == null || listMessages == null || progressBar == null || activity == null)
            return;

        if (thread == null)
        {
            return;
        }

        if (hideListView)
        {
            listMessages.setVisibility(View.INVISIBLE);
            progressBar.setVisibility(View.VISIBLE);
        }
        else progressBar.setVisibility(View.INVISIBLE);

        ChatSDKThreadPool.getInstance().execute(new Runnable() {
            @Override
            public void run() {

                if (!hasActivity())
                    return;
                
                final int oldDataSize = messagesListAdapter.getCount();

                List<BMessage> messages;
                // Loading messages
                // Load with fixed limit
                if (amountToLoad > 0) {
                    messages = getMessagesForThreadID(thread.getId(), amountToLoad);
                }

                // we all read loaded messages so we load more then the default limit.
                else if (messagesListAdapter.getCount() > Defines.MAX_MESSAGES_TO_PULL + 1) {
                    messages = getMessagesForThreadID(thread.getId(), messagesListAdapter.getCount());
                }

                //This value is saved in the savedInstanceState so we could check if there was more loaded messages then normal before.
                else if (loadedMessagesAmount > Defines.MAX_MESSAGES_TO_PULL + 1) {
                    messages = getMessagesForThreadID(thread.getId(), loadedMessagesAmount);
                }
                //Loading with default limit.
                else {
                    messages = getMessagesForThreadID(thread.getId());
                }

                // Sorting the message by date to make sure the list looks ok.
                Collections.sort(messages, new MessageSorter(MessageSorter.ORDER_TYPE_DESC));

                loadedMessagesAmount = messages.size();

                markAsRead(messages);

                // Setting the new message to the adapter.
                final List<MessagesListAdapter.MessageListItem> list = messagesListAdapter.makeList(messages);

                if (list.size() == 0)
                {
                    activity.get().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.INVISIBLE);
                            listMessages.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }

                activity.get().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        messagesListAdapter.setListData(list);

                        // restoring the old position after the load is done.
                        if (retain)
                        {
                            int newDataSize = messagesListAdapter.getCount();
                            final int index = listMessages.getFirstVisiblePosition() + newDataSize - oldDataSize + offsetOrPos;
                            View v = listMessages.getChildAt(0);
                            final int top = (v == null) ? -1 : v.getTop();

                            listMessages.post(new Runnable() {
                                @Override
                                public void run() {
                                    listMessages.setSelectionFromTop(index, top);

                                    if (listMessages.getVisibility() == View.INVISIBLE)
                                        animateListView();
                                }
                            });
                        }
                        // If list view is visible smooth scroll else dirty.
                        else scrollListTo(offsetOrPos,  !hideListView);
                    }
                });
            }
        });
    }

    /**
     * Get all messages for given thread id ordered Ascending/Descending
     */
    public List<BMessage> getMessagesForThreadID(Long id) {
        return getMessagesForThreadID(id, Defines.MAX_MESSAGES_TO_PULL);
    }

    /**
     * Get all messages for given thread id ordered Ascending/Descending
     */
    public List<BMessage> getMessagesForThreadID(Long id, int limit) {
        List<BMessage> list ;

        QueryBuilder<BMessage> qb = DaoCore.daoSession.queryBuilder(BMessage.class);
        qb.where(BMessageDao.Properties.ThreadId.eq(id));

        // Making sure no null messages infected the sort.
        qb.where(BMessageDao.Properties.Date.isNotNull());
        qb.where(BMessageDao.Properties.SenderId.isNotNull());

        qb.orderDesc(BMessageDao.Properties.Date);

        if (limit != -1)
            qb.limit(limit);

        list = qb.list();

        return list;
    }
    
    public void markAsRead(List<BMessage> messages){
        for (BMessage m : messages)
        {
            m.setIsRead(true);
            DaoCore.updateEntity(m);
            readCount++;
        }
    }

    public void markAsRead(BMessage message){
        message.setIsRead(true);
        DaoCore.updateEntity(message);
        readCount++;
    }

    public void loadMessagesAndScrollBottom(){
        loadMessages(false, true, - 1, 0);
    }

    public void loadMessages(int scrollingPos){
        loadMessages(false, true, scrollingPos, 0);
    }

    public void scrollListTo(final int pos, final boolean smooth) {
        if (!hasActivity())
            return;
        
        if (listMessages == null)
            return;

        listMessages.post(new Runnable() {
            @Override
            public void run() {
                // Select the last row so it will scroll into view...
                int position = pos;
                if (pos == -1)
                    position = messagesListAdapter.getCount()-1;

                if (smooth)
                    listMessages.smoothScrollToPosition(position);
                else
                    listMessages.setSelection(position);

                if (listMessages.getVisibility() == View.INVISIBLE)
                    animateListView();
            }
        });
    }

    public void animateListView() {

        if (!hasActivity())
            return;
        
        if (listMessages == null)
            return;

        if (DEBUG) Timber.v("animateListView");

        listMessages.setAnimation(AnimationUtils.loadAnimation(activity.get(), R.anim.fade_in_expand));
        listMessages.getAnimation().setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                if (progressBar!= null)
                    progressBar.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                listMessages.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        listMessages.getAnimation().start();
    }

    private void sendingMessageToast(){
        // Just to be sure it's initialized.
        uiHelper.initCardToast();
        uiHelper.showProgressCard("Sending...");
    }

    public void onSavedInstanceBundle(Bundle outState){
        if (StringUtils.isNotEmpty(selectedFilePath))
        {
            outState.putString(SELECTED_FILE_PATH, selectedFilePath);
        }

        outState.putInt(LOADED_MESSAGES_AMOUNT, loadedMessagesAmount);
        outState.putInt(READ_COUNT, readCount);
        outState.putString(FILE_NAME, mFileName);

        SuperCardToast.onSaveState(outState);
    }
    
    public void restoreSavedInstance(Bundle savedInstanceState){
        if (savedInstanceState == null)
            return;
        
        if (!hasActivity())
            return;

        selectedFilePath = savedInstanceState.getString(SELECTED_FILE_PATH);
        loadedMessagesAmount = savedInstanceState.getInt(LOADED_MESSAGES_AMOUNT, 0);
        readCount = savedInstanceState.getInt(READ_COUNT);
        mFileName = savedInstanceState.getString(FILE_NAME);

        SuperCardToast.onRestoreState(savedInstanceState, activity.get());
    }

    public void setThread(BThread thread) {
        this.thread = thread;
    }

    public void setListMessages(ListView listMessages) {
        this.listMessages = listMessages;
    }

    public void setProgressBar(ProgressBar progressBar) {
        this.progressBar = progressBar;
    }

    public void setMessagesListAdapter(MessagesListAdapter messagesListAdapter) {
        this.messagesListAdapter = messagesListAdapter;
    }

    public int getReadCount() {
        return readCount;
    }
    
    public boolean hasActivity(){
        return  activity != null && activity.get() != null;
        
    }
}