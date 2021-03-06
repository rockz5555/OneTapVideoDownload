package com.phantom.onetapvideodownload.downloader;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.crash.FirebaseCrash;
import com.phantom.onetapvideodownload.R;
import com.phantom.onetapvideodownload.downloader.downloadinfo.DownloadInfo;
import com.phantom.utils.Global;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class DownloadHandler {
    private final static String TAG = "DownloadHandler";
    private final static Long UPDATE_PROGRESS_TIME = 3000L;
    private final static Long READ_BUFFER_SIZE = 2048L;
    private Context mContext;
    private DownloadInfo mDownloadInfo;
    private static long lastWriteTime = System.currentTimeMillis();
    private Call mCall;

    DownloadHandler(Context context, DownloadInfo downloadInfo) {
        mContext = context;
        mDownloadInfo = downloadInfo;
    }

    void startDownload() {
        File filePath = new File(mDownloadInfo.getDownloadLocation());
        setStatus(DownloadInfo.Status.Downloading);
        downloadFile(mDownloadInfo.getUrl(), filePath);
        mContext.startService(DownloadManager.getActionUpdateUi());
    }

    private void downloadFile(String url, File file) {
        if (Global.isLocalFile(url)) {
            handleLocalFileDownload(url, file);
        } else {
            handleRemoteFileDownload(url, file);
        }
    }

    private void handleLocalFileDownload(String url, final File file) {
        final File sourceFile = new File(url);
        if (!sourceFile.exists()) {
            Toast.makeText(mContext, R.string.link_expired_download_again, Toast.LENGTH_LONG).show();
            setStatus(DownloadInfo.Status.NetworkProblem);
            writeToDatabase();
            return;
        }

        mDownloadInfo.setContentLength(sourceFile.length());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedSource bufferedSource = Okio.buffer(Okio.source(sourceFile));
                    bufferedSource.skip(mDownloadInfo.getDownloadedLength());
                    BufferedSink bufferedSink = Okio.buffer(Okio.appendingSink(file));
                    writeData(bufferedSource, bufferedSink);
                } catch (Exception e) {
                    FirebaseCrash.report(e);
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void handleRemoteFileDownload(String url, final File file) {
        if (Global.isInternetAvailable(mContext)) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=" + getDownloadedLength() + "-")
                    .build();

            mCall = client.newCall(request);
            mCall.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    setStatus(DownloadInfo.Status.NetworkProblem);
                    writeToDatabase();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            BufferedSource bufferedSource = response.body().source();
                            if (!started()) {
                                mDownloadInfo.setContentLength(response.body().contentLength());
                            }

                            BufferedSink bufferedSink = Okio.buffer(Okio.appendingSink(file));;
                            writeData(bufferedSource, bufferedSink);
                        } else {
                            stopDownload();
                            String message = "Download Failed! Response Code : " + response.code();
                            Log.v(TAG, message);
                            Global.showToastFromNonUiThread(mContext, message, Toast.LENGTH_SHORT);
                        }
                    } catch (IOException e) {
                        Log.e("DownloadService", "Exception : ", e);
                        stopDownload();
                    }
                }
            });
        } else {
            Toast.makeText(mContext, R.string.internet_not_available_summary, Toast.LENGTH_LONG).show();
            setStatus(DownloadInfo.Status.NetworkNotAvailable);
            writeToDatabase();
        }
    }

    private void writeData(BufferedSource bufferedSource, BufferedSink bufferedSink) {
        try {
            long count, currentTime;
            while ((count = (bufferedSource.read(bufferedSink.buffer(), READ_BUFFER_SIZE))) != -1) {
                mDownloadInfo.addDownloadedLength(count);
                currentTime = System.currentTimeMillis();
                bufferedSink.flush();
                if (currentTime - lastWriteTime > UPDATE_PROGRESS_TIME) {
                    writeToDatabase();
                    lastWriteTime = currentTime;
                }
            }

            bufferedSink.writeAll(bufferedSource);
            bufferedSink.flush();
            bufferedSink.close();
            bufferedSource.close();

            setStatus(DownloadInfo.Status.Completed);
            Global.runMediaScanner(mContext, new String[] { mDownloadInfo.getDownloadLocation() });
            writeToDatabase();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                if (bufferedSource != null) {
                    bufferedSource.close();
                }

                if (bufferedSink != null) {
                    bufferedSink.close();
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
                FirebaseCrash.report(ioException);
            }
        } finally {
            writeToDatabase();
        }
    }

    Integer getProgress() {
        return mDownloadInfo.getProgress();
    }

    DownloadInfo.Status getStatus() {
        return mDownloadInfo.getStatus();
    }

    void setStatus(DownloadInfo.Status status) {
        mDownloadInfo.setStatus(status);
    }

    long getContentLength() {
        return mDownloadInfo.getContentLength();
    }

    String getFilename() {
        return mDownloadInfo.getFilename();
    }

    String getUrl() {
        return mDownloadInfo.getUrl();
    }

    Drawable getPackageDrawable() {
        try {
            Drawable d = mContext.getPackageManager().getApplicationIcon(mDownloadInfo.getPackageName());
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    Collection<String> getOptions() {
        return mDownloadInfo.getOptions();
    }

    int findIdByString(Context context, String string) {
        return mDownloadInfo.findIdByString(context, string);
    }

    boolean handleOptionClicks(Context context, int resId) {
        // Used Activity context instead of ApplicationContext
        return mDownloadInfo.handleOptionClicks(context, resId);
    }

    long getDownloadedLength() {
        return mDownloadInfo.getDownloadedLength();
    }

    boolean started() {
        return getDownloadedLength() != 0;
    }

    void stopDownload() {
        if (mCall != null) {
            mCall.cancel();
        }
        setStatus(DownloadInfo.Status.Stopped);
        writeToDatabase();
    }

    void writeToDatabase() {
        mDownloadInfo.writeToDatabase();
    }

    long getDatabaseId() {
        return mDownloadInfo.getDatabaseId();
    }

    void removeDownloadFromDatabase() {
        mDownloadInfo.removeDatabaseEntry();
    }

    void deleteDownloadFromStorage() {
        mDownloadInfo.deleteDownloadFromStorage(mContext);
    }
}
