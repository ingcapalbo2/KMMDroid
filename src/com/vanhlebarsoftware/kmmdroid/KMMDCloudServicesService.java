package com.vanhlebarsoftware.kmmdroid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.xmlpull.v1.XmlSerializer;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.DropboxAPI.DeltaEntry;
import com.dropbox.client2.DropboxAPI.DeltaPage;
import com.dropbox.client2.DropboxAPI.DropboxFileInfo;
import com.dropbox.client2.DropboxAPI.Entry;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.exception.DropboxServerException;
import com.dropbox.client2.exception.DropboxUnlinkedException;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.Session.AccessType;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.sax.Element;
import android.sax.EndElementListener;
import android.sax.EndTextElementListener;
import android.sax.RootElement;
import android.util.Log;
import android.util.Xml;

public class KMMDCloudServicesService extends Service 
{
	private static final String TAG = KMMDCloudServicesService.class.getSimpleName();
    final static private String ACCOUNT_PREFS_NAME = "prefs";
    final static private String ACCESS_KEY_NAME = "ACCESS_KEY";
    final static private String ACCESS_SECRET_NAME = "ACCESS_SECRET";
    final static private String DEVICE_STATE_FILE = "device_state";
	public static final int CLOUD_DROPBOX = 0;
	public static final int CLOUD_GOOGLEDRIVE = 1;
	public static final int CLOUD_UBUNTUONE = 2;
	public static final int CLOUD_NOTIFICATION = 1001;
	private KMMDDropboxUpdater kmmdDropbox;
	private NotificationManager kmmdNotifcationMgr;
	private Notification kmmdNotification;
	DropboxAPI<AndroidAuthSession> mApi;
	boolean m_LoggedIn = false;
	boolean syncing = false;
	
	@Override
	public void onCreate() 
	{
		super.onCreate();
		this.kmmdDropbox = new KMMDDropboxUpdater();
		
		// First let get the authorization keys that have been stored.
		String[] token = getKeys();
		AccessTokenPair access;
		// We create a new AuthSession so that we can use the Dropbox API.
		AppKeyPair appKeys = new AppKeyPair(getString(R.string.app_key), getString(R.string.app_secret));
		AndroidAuthSession session = new AndroidAuthSession(appKeys, AccessType.APP_FOLDER);
		mApi= new DropboxAPI<AndroidAuthSession>(session);
		if(token != null)
		{
			access = new AccessTokenPair(token[0], token[1]);
        	mApi.getSession().setAccessTokenPair(access);
        	m_LoggedIn = true;
		}
	}

	@Override
	public void onDestroy() 
	{
		super.onDestroy();
		
		// Remove our notification when we are done.
		this.kmmdNotifcationMgr.cancel(CLOUD_NOTIFICATION);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) 
	{
		super.onStartCommand(intent, flags, startId);

		Bundle extras = intent.getExtras();
		//Get the service we are going to login/logout from and/or to sync with
		//along with the actual action we are going to perform.
		int cloudService = extras.getInt("cloudService");
		syncing = extras.getBoolean("syncing");
		
		// Set the notification to let the user know we are performing a sync.
		this.kmmdNotifcationMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		this.kmmdNotification = new Notification(R.drawable.homewidget_icon, "", 0);
		this.kmmdNotification.when = System.currentTimeMillis();
		this.kmmdNotification.flags |= Notification.FLAG_NO_CLEAR;
		String notificationTitle = "KMMDroid Sync";
		String notificationSummary = "Syncing with ";
		
		switch(cloudService)
		{
		case CLOUD_DROPBOX:
			notificationSummary = notificationSummary + "Dropbox.....";
			kmmdDropbox.start();
			break;
		case CLOUD_GOOGLEDRIVE:
			break;
		case CLOUD_UBUNTUONE:
			break;
		}

		this.kmmdNotification.setLatestEventInfo(this, notificationTitle, notificationSummary, PendingIntent.getActivity(getBaseContext(), 0, null, 0));
		this.kmmdNotifcationMgr.notify(CLOUD_NOTIFICATION, this.kmmdNotification);
		
		return START_NOT_STICKY;
	}
	@Override
	public IBinder onBind(Intent intent) 
	{
		// TODO Auto-generated method stub
		return null;
	}

	/****************************************************************************************************************
	 * Helper Functions
	 ***************************************************************************************************************/	
	private void performSync(int service)
	{
		/********************************************************************
		 * We are going to pull the files from each cloud service seperately
		 * and then compare each listing to our local listing to see if we
		 * need to upload or download the particular file.
		 *******************************************************************/
		/************************************
		 * Need to look at this routine for better performance and using /delta
		 * from Dropbox, especially when we start doing alarms for syncing.
		 *************************************/		
		/*************************************************************
		 * When a change is made to any database it is marked as dirty
		 * in the preferences file and stored as a string like this:
		 * Dropbox:GoogleDrive:UbuntuOne
		 * 1:1:1
		 * 1 - means database is dirty and needs to be uploaded.
		 * 0 - means database is not dirty and does not need to be uploaded
		 * 
		 * The "hash" or Rev from each cloud service is stored as a string
		 * similar to the way we store the fact a file needs to be uploaded.
		 * Dropbox:GoogleDrive:UbuntuOne
		 ************************************************************/
		
		boolean hasMorePages = true;
		DeltaPage<Entry> dropboxChanges = null;
		
		switch( service )
		{
		case CLOUD_DROPBOX:
			while( hasMorePages )
			{
				dropboxChanges = getDropboxChanges();
				// if files is empty we have nothing locally and we can just do the dropbox changes.
				//if(files.length == 0)
				//	performDropboxChanges(dropboxChanges);
				// if dropboxChanges is empty we have no changes on the service, just upload local maybe.
				//else if( !dropboxChanges.isEmpty() )
				//	upload(files);
				// we have a mixed bag and need to do more work now.
				//else
				applyDropboxDeltaPages(dropboxChanges);
				// See if we have more pages to retrieve
				hasMorePages = dropboxChanges.hasMore;
			}
			break;
		case CLOUD_GOOGLEDRIVE:
			break;
		case CLOUD_UBUNTUONE:
			break;
		}

		// Need to handle our changes in the device state from the xml file.
		List<DeviceItem> deviceState = new ArrayList<DeviceItem>();
		// Get our CURRENT device state
		deviceState.addAll(getDeviceState(Environment.getExternalStorageDirectory() + "/KMMDroid"));
		// Make our changes to the cloud service
		syncDeviceState(deviceState);
		// Write our CURRENT device state to xml file.
		writeDeviceState(deviceState);
	}
	
	private boolean upload(String fileName)
	{
		File KMMDroidDirectory = new File(Environment.getExternalStorageDirectory(), "/KMMDroid");
		String prevRev = null;
		String individualPrevRev[] = { null, null, null };
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor editor = prefs.edit();
		
		// Get any previous revs we might have.
		prevRev = prefs.getString(fileName + "prevRev", null);
		if(prevRev != null)
			individualPrevRev = prevRev.split(":");
			
		FileInputStream inputStream = null;
		try 
		{
		    File file = new File(KMMDroidDirectory, fileName);
		    inputStream = new FileInputStream(file);
		    Entry newEntry = mApi.putFile("/" + fileName, inputStream,
		            file.length(), individualPrevRev[CLOUD_DROPBOX], null);
		    Log.i(TAG, "The uploaded file's rev is: " + newEntry.rev);
		    // Need to store the key value pair of the file name and rev hash in the prefences so that we can use it later
		    // to see if the file has changed or not to see if we need to redownload or upload the file.
			editor.putString(newEntry.fileName() + "prevRev", newEntry.rev);
			editor.apply();
		} 
		catch (DropboxUnlinkedException e) 
		{
		    // User has unlinked, ask them to link again here.
		    Log.e(TAG, "User has unlinked. : " + e.getMessage());
		    return false;
		} 
		catch (DropboxException e) 
		{
		    Log.e(TAG, "Something went wrong while uploading. - " + e.getMessage());
		    return false;
		} 
		catch (FileNotFoundException e) 
		{
		    Log.e(TAG, "File not found. - " + e.getMessage());
		    return false;
		} 
		finally 
		{
		    if (inputStream != null) 
		    {
		        try 
		        {
		            inputStream.close();
		        } 
		        catch (IOException e) {}
		    }
		}
		
		return true;
	}
	
	private void download(String fileName)
	{
		File KMMDroidDirectory = new File(Environment.getExternalStorageDirectory(), "/KMMDroid");
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
		
		// Now we need to download the database file from our Dropbox folder.
		FileOutputStream outputStream = null;

		try 
		{
			// create a File object for the parent directory
			File file = new File(KMMDroidDirectory, fileName);
			Log.d(TAG, "Attempting to create file at: " + file.getAbsolutePath());
			outputStream = new FileOutputStream(file);
			// Get the actual file from the service
			DropboxFileInfo fileInfo = mApi.getFile("/" + fileName, null, outputStream, null);
			// Now store the file name along with the rev code so we have it for later.
			edit.putString(fileName, fileInfo.getMetadata().rev);
			edit.apply();
			Log.d(TAG, "Sucessfully downloaded: " + fileInfo.getMetadata().fileName() + " from Dropbox!");
			Log.d(TAG, "File was saved at: " + file.getAbsolutePath());
			Log.d(TAG, "The file's rev is: " + fileInfo.getMetadata().rev);
		} 
		catch (DropboxException e) 
		{
			e.printStackTrace();
			Log.d(TAG, "There was an error downloading the file: " + fileName + ": error code: " + e.getMessage());
		} 
		catch (FileNotFoundException e) 
		{
			Log.e("DbExampleLog", e.getMessage());
		} 
		finally 
		{
			if (outputStream != null) 
			{
				try 
				{
					outputStream.close();
				} 
				catch (IOException e) {}
			}
		}				
	}
	
	private void delete(DeviceItem removedItem)
	{
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        
        // Try and delete the item from the service.
        try
        {
        	mApi.delete(removedItem.getServerPath());
        }
        catch (DropboxServerException e)
        {
        	switch(e.error)
        	{
        	case DropboxServerException._404_NOT_FOUND:
        		Log.d(TAG, "Dropbox returned: " + e.getMessage().toString());
        		break;
        	}
        }
        catch (DropboxException e)
        {
        	Log.d(TAG, "Error deleting item: " + removedItem.getPath() + " - error given: " + e.getMessage());
        }
        
        // Remove the path from the preferences
    	edit.remove(removedItem.getPath());
    	edit.apply();
	}
	
	private void applyDropboxDeltaPages(DeltaPage<Entry> dropboxChanges)
	{
    	// Read in the saved deviceState from the xml file and put it into a List<>.
    	List<DeviceItem> savedDeviceState = new ArrayList<DeviceItem>();
    	DeviceItemParser parser = new DeviceItemParser(DEVICE_STATE_FILE);
    	savedDeviceState = parser.parse();
    	
		// Because the service give us our App Folder of "/KMMDroid" we only need to get the path of the sd card.
		File KMMDroidDirectory = new File(Environment.getExternalStorageDirectory(), "/KMMDroid");
        
		// We will work our way through the proposed Dropbox changes and see if we also need to upload the same file.
		// If we have a conflict then we will rename the local file like this: filname-MMDDYY:HH-MM-SS.sqlite
		// Then we will download the proposed change and upload the new file.
		// If we have no local file/folder for a proposed change, then we will download it and
		// if we have a file/folder locally that is not on the server then upload it.
		for(DeltaEntry<DropboxAPI.Entry> entry : dropboxChanges.entries)
		{
			Entry metaData = entry.metadata;

			// if Metadata is null then Dropbox has deleted the file/folder and we need to do the same on our device.
			if(metaData == null)
			{
				boolean haveConflict = false;
				DeviceItem foundItem = null;
				
				// See if we still have this folder/file in our saved state, if so we need to delete it, if not skip it.
				for(DeviceItem item : savedDeviceState)
					foundItem = item.findMatch(entry.lcPath);

				if(foundItem != null)
				{
					if(foundItem.getType().equalsIgnoreCase("Folder"))
					{
						// We need to delete the files first in the directory, then delete the directory itself IF it already exists.
						File folder = new File(foundItem.getPath());

							// We must have the directory locally, so get any files, delete them then the directory itself.
							File[] files = folder.listFiles();
							for(int f=0; f<files.length; f++)
							{
								if(isFileDirty(files[f].getAbsolutePath(), CLOUD_DROPBOX))
								{
									// We have a conflict so rename our device file and then upload and delete our device file of original name.
									// get the current date
									final Calendar c = Calendar.getInstance();
									String[] filename = files[f].getName().split(".");
									File newFile = new File(filename[0] + "-" + String.valueOf(c.get(Calendar.MONTH)+ 1) + 
														String.valueOf(c.get(Calendar.DAY_OF_MONTH)) +
						        						String.valueOf(c.get(Calendar.YEAR)) + ":" +
						        						String.valueOf(c.get(Calendar.HOUR_OF_DAY)) + "-" +
						        						String.valueOf(c.get(Calendar.MINUTE)) + "-" +
						        						String.valueOf(c.get(Calendar.SECOND)) + filename[1]);
									files[f].renameTo(newFile);
									if( !upload(foundItem.getServerPath() + files[f].getName()) )
									{
										// We had an issue uploading our file we need to handle this.
									}
									
									// Delete the file the server has removed.
									files[f].delete();
									haveConflict = true;
								}
								else
								{
									// Our device file is clean and service says to delete the file so we shall.
									files[f].delete();
								}
							}
						
							// We can only delete the parent folder if we have no conflicts.
							if(!haveConflict)
								folder.delete();
										
					}
					else
					{
						File file = new File(foundItem.getPath());	
						if( isFileDirty(foundItem.getPath(), CLOUD_DROPBOX) )
						{
							// We have a conflict so rename our device file and then upload and delete our device file of original name.
							// get the current date
							final Calendar c = Calendar.getInstance();
							String[] filename = file.getName().split(".");
							File newFile = new File(filename[0] + "-" + String.valueOf(c.get(Calendar.MONTH)+ 1) + 
												String.valueOf(c.get(Calendar.DAY_OF_MONTH)) +
				        						String.valueOf(c.get(Calendar.YEAR)) + ":" +
				        						String.valueOf(c.get(Calendar.HOUR_OF_DAY)) + "-" +
				        						String.valueOf(c.get(Calendar.MINUTE)) + "-" +
				        						String.valueOf(c.get(Calendar.SECOND)) + filename[1]);
							file.renameTo(newFile);
							if( !upload(foundItem.getServerPath() + file.getName()) )
							{
								// We had an issue uploading our file we need to handle this.
							}
							
							// Delete the file the server has removed.
							file.delete();
							haveConflict = true;
						}	
						else
							file.delete();			
					}
				}
			}
			// if Metadata is NOT null then we need to download file or create the folder locally.
			else
			{
				// if we have a folder then try and create it on our device
				if(metaData.isDir)
				{
					// If we have our AppFolder of /KMMDroid then we can skip it, otherwise try to create it.
					if(metaData.fileName().equalsIgnoreCase("KMMDroid"))
						Log.d(TAG, "We have our App Folder, skipping it.");
					else
					{
						File file = new File(KMMDroidDirectory, metaData.path);
						Log.d(TAG, "Attempting to create directory: " + file.getAbsolutePath());
						file.mkdirs();
					}
				}
				// If we have a file, see if we have a conflict b/c our local device isDirty and download service file or handle conflict.
				else
				{
					if(isFileDirty(metaData.path, CLOUD_DROPBOX))
					{
						// We have a conflict so rename our device file and then upload and delete our device file of original name.
				        // get the current date
				        final Calendar c = Calendar.getInstance();
				        File localFile = new File(metaData.path);
				        String[] filename = metaData.fileName().split(".");
				        File newFile = new File(filename[0] + "-" + String.valueOf(c.get(Calendar.MONTH)+ 1) + 
				        						String.valueOf(c.get(Calendar.DAY_OF_MONTH)) +
				        						String.valueOf(c.get(Calendar.YEAR)) + ":" +
				        						String.valueOf(c.get(Calendar.HOUR_OF_DAY)) + "-" +
				        						String.valueOf(c.get(Calendar.MINUTE)) + "-" +
				        						String.valueOf(c.get(Calendar.SECOND)) + filename[1]);
				        localFile.renameTo(newFile);
				        // upload the new localfile to the service.
				        upload(localFile.getPath());
				        
				        // download the services file.
				        download(metaData.path);
					}
					else
					{
						// No conflict so just download the file.
						download(metaData.path);
					}
				}
			}
		}		
	}
	
	private DeltaPage<Entry> getDropboxChanges()
	{
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        DeltaPage<Entry> deltaEntries;
        
        // Get our previously stored cursor from our previous call to delta.
        String dbCursor = prefs.getString("dropboxCursor", null);
        
		try 
		{
			deltaEntries = mApi.delta(dbCursor);
		} 
		catch (DropboxException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		
		// Save the last returned dbCursor.
		edit.putString("dropboxCursor", deltaEntries.cursor);
		edit.apply();
		
		return deltaEntries;
	}
	
    private String[] getKeys()
    {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key != null && secret != null)
        {
        	String[] ret = new String[2];
        	ret[0] = key;
        	ret[1] = secret;
        	return ret;
        }
        else
        {
        	return null;
        }
    }

    private boolean isFileDirty(String serviceFullPath, int service)
    {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
	
        String[] fileDirty = prefs.getString(serviceFullPath, "0:0:0").split(":");

        if(fileDirty[service].equalsIgnoreCase("1"))
        	return true;
        else
        	return false;
    }
    
    private List<DeviceItem> getDeviceState(String folderPath)
    {
    	List<DeviceItem> files = new ArrayList<DeviceItem>();
		File startFolder = new File(folderPath);
		// Get the top level files and any folders that might be here
		File[] dirs = startFolder.listFiles();
		
		try
		{
			for(File file : dirs)
			{
				if(file.isDirectory())
				{
					files.add(new DeviceItem(file.getName(), "Folder", file.getAbsolutePath()));
					files.addAll(getDeviceState(file.getAbsolutePath()));
				}
				else
					files.add(new DeviceItem(file.getName(), "File", file.getAbsolutePath()));
			}
		}
		catch (Exception e)
		{
			
		}
		//Collections.sort(files);
		
		return files;
    }
    
    private void syncDeviceState(List<DeviceItem> currentDeviceState)
    {
    	DeviceItem diItem;
    	List<DeviceItem> itemToRemove = new ArrayList<DeviceItem>();
    	List<DeviceItem> itemsToUpload = new ArrayList<DeviceItem>();
    	boolean itemMatched = false;
    	
    	// Read in the saved deviceState from the xml file and put it into a List<>.
    	List<DeviceItem> savedDeviceState = new ArrayList<DeviceItem>();
    	DeviceItemParser parser = new DeviceItemParser(DEVICE_STATE_FILE);
    	savedDeviceState = parser.parse();
    	
    	// If savedDeviceState is null then we didn't have a stateFile yet OR somehow it got deleted, so just skip this and don't update
    	// the service with our current device state.
    	if( savedDeviceState != null)
    	{ 
        	// loop over each ArrayList to compare each item and see if we need to remove any from the service.
    		for(int i=0; i<savedDeviceState.size(); i++)
    		{
    			diItem = savedDeviceState.get(i);
    			for(int j=0; j<currentDeviceState.size(); j++)
    			{
    				itemMatched = diItem.equals(currentDeviceState.get(j));
    			
    				if(itemMatched)
    					j = currentDeviceState.size();
    			}
    		
    			if(!itemMatched)
    				itemToRemove.add(diItem);
    			
    			diItem = null;
    		}
    	
    		// Now we have our items to remove, remove them from the Service
    		if(itemToRemove.size() > 0)
    		{
    			for(DeviceItem item : itemToRemove)
    				delete(item);
    		}
    		
    		// loop over each ArrayList to compare each item and see if we have anything in our current state that needs to be uploaded.
    		for(int i=0; i<currentDeviceState.size(); i++)
    		{
    			diItem = currentDeviceState.get(i);
    			for(int j=0; j<savedDeviceState.size(); j++)
    			{
    				itemMatched = diItem.equals(savedDeviceState.get(j));
    			
    				if(itemMatched)
    					j = savedDeviceState.size();
    			}
    		
    			if(!itemMatched)
    				itemsToUpload.add(diItem);
    			
    			diItem = null;
    		}
    		
    		// Now we have any items that need to be uploaded to the service.
    		Log.d(TAG, "Files to upload: " + itemsToUpload.size());
    		if(itemsToUpload.size() > 0)
    		{
    			for(DeviceItem item : itemsToUpload)
    			{
    				Log.d(TAG, "File we are uploading: " + item.getServerPath());
    				upload(item.getServerPath());
    			}
    		}
    	}
    }
    
    private void writeDeviceState(List<DeviceItem> deviceState)
    {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();
        try 
        {
            serializer.setOutput(writer);
            serializer.startDocument("UTF-8", true);
            serializer.startTag("", "DeviceState");
            for (DeviceItem item: deviceState)
            {
            	Log.d(TAG, "Testing getServerPath(): " + item.getServerPath());
           		serializer.startTag("", "item");
           		serializer.startTag("", "type");
           		serializer.text(item.getType());
           		serializer.endTag("", "type");
           		serializer.startTag("", "name");
           		serializer.text(item.getName());
           		serializer.endTag("", "name");
           		serializer.startTag("", "path");
           		serializer.text(item.getPath());
           		serializer.endTag("", "path");
           		serializer.endTag("", "item");
            }
            serializer.endTag("", "DeviceState");
            serializer.endDocument();
        } 
        catch (Exception e) 
        {
            throw new RuntimeException(e);
        }
        
        // Attempt to write the state file to the private storage area.
        String FILENAME = DEVICE_STATE_FILE;
        try 
        {
			FileOutputStream fos = openFileOutput(FILENAME, Context.MODE_PRIVATE);
			fos.write(writer.toString().getBytes());
			fos.close();
		} 
        catch (FileNotFoundException e) 
        {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
        catch (IOException e) 
        {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

	private class DeviceItem implements Comparable<DeviceItem>
	{
		private String name;
		private String type;
		private String path;
		
		public DeviceItem()
		{
			this.name = null;
			this.type = null;
			this.path = null;
		}
		public DeviceItem(String n, String t, String p)
		{
			this.name = n;
			this.type = t;
			this.path = p;
		}
		
		public String getName()
		{
			return this.name;
		}
		
		public void setName(String n)
		{
			this.name = n;
		}
		
		public String getType()
		{
			return this.type;
		}
		
		public void setType(String t)
		{
			this.type = t;
		}
		
		public String getPath()
		{
			return this.path;
		}
		
		public void setPath(String p)
		{
			this.path = p;
		}
		
		public String getServerPath()
		{
			int start = this.path.indexOf("/KMMDroid");
			return this.path.substring(start).substring(9);
		}
		
		public boolean equals(DeviceItem diItem)
		{
			Log.d(TAG, "Comapring the following two items:");
			Log.d(TAG, this.toString());
			Log.d(TAG, diItem.toString());
			
			if(this.getType().equals(diItem.getType()))
			{
				// We have the same type (File or Folder) now see if the path is the same, return true if yes, false if not.
				Log.d(TAG, "Doing comparaison of the two paths.");
				return this.getPath().equals(diItem.getPath());
			}
			else
			{
				Log.d(TAG, "We didn't have the same type, no comparison done!");
				return false;
			}
		}
		
		public int compareTo(DeviceItem o)
		{			
			if(this.name != null)
				return this.name.toLowerCase().compareTo(o.getName().toLowerCase());
			else
				throw new IllegalArgumentException();
		}
		
		public String toString()
		{
			String tmp = "Type: " + this.type + " Name: " + this.name + " Path: " + this.path + "Server Path: " + this.getServerPath();
			return tmp;
		}
		
		public DeviceItem copy()
		{
			DeviceItem tmp = new DeviceItem(this.name, this.type, this.path);
			return tmp;
		}
		
		public DeviceItem findMatch(String path)
		{
			// Returns the current DeviceItem if our path matches the path of this DeviceItem, otherwise returns null.
			if(this.path.equalsIgnoreCase(path))
				return this;
			else
				return null;
		}
	}
	
	private abstract class BaseDeviceItemParser implements DeviceStateParser
	{
		// names of our XML tags
		static final String ITEM = "item";
		static final String NAME = "name";
		static final String PATH = "path";
		static final String TYPE = "type";		
		final String xmlDeviceState;
		
		protected BaseDeviceItemParser(String deviceStateFile)
		{
			this.xmlDeviceState = deviceStateFile;
		}
		
		protected FileInputStream getInputStream()
		{
			try
			{
				return openFileInput(this.xmlDeviceState);	
			}
	        catch (FileNotFoundException e) 
	        {
	        	return null;
			} 
		}
	}
	
	private class DeviceItemParser extends BaseDeviceItemParser
	{
		public DeviceItemParser(String xmlFile)
		{
			super(xmlFile);
		}
		
		public List<DeviceItem> parse()
		{
			final DeviceItem currentDeviceItem = new DeviceItem();
			final List<DeviceItem> deviceItems = new ArrayList<DeviceItem>();
			RootElement root = new RootElement("DeviceState");
			Element item = root.getChild(ITEM);
			
			item.setEndElementListener(new EndElementListener()
			{
				public void end()
				{
					Log.d(TAG, "currentDeviceItem: " + currentDeviceItem.toString());
					deviceItems.add(currentDeviceItem.copy());
				}
			});
			
			item.getChild(NAME).setEndTextElementListener(new EndTextElementListener()
			{
				public void end(String name)
				{
					currentDeviceItem.setName(name);
				}
			});
			
			item.getChild(PATH).setEndTextElementListener(new EndTextElementListener()
			{
				public void end(String path)
				{
					currentDeviceItem.setPath(path);
				}
			});
			
			item.getChild(TYPE).setEndTextElementListener(new EndTextElementListener()
			{
				public void end(String data)
				{
					currentDeviceItem.setType(data);
				}
			});
			
			try
			{
				Xml.parse(this.getInputStream(), Xml.Encoding.UTF_8, root.getContentHandler());
			}
			catch (Exception e)
			{
				return null;
			}
			
			return deviceItems;
		}
	}
	
	public interface DeviceStateParser
	{
		List<DeviceItem> parse();
	}
    /**************************************************************************************************************
	 * Thread that will perform the actual syncing with Dropbox
	 *************************************************************************************************************/
	private class KMMDDropboxUpdater extends Thread
	{

		public KMMDDropboxUpdater()
		{
			super("KMMDDropboxUpdater-Updater");
		}
		
		@Override
		public void run()
		{
			performSync(CLOUD_DROPBOX);			
			stopSelf();
		}
	}
}
