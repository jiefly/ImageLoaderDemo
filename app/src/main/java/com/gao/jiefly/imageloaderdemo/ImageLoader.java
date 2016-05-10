package com.gao.jiefly.imageloaderdemo;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by jiefly on 2016/5/9.
 * Fighting_jiiiiie
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";
    //暂时
    private static final int TAG_KEY_URL = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int MESSAGE_POST_RESULT = 1;
    private LruCache<String, Bitmap> mLruCache;
    private DiskLruCache mDiskLruCache;
    private Context mContext;
    private boolean ismDiskLruCacheIsCreated = false;

    //线程池所用到的参数
    //cup数量
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    //线程池中核心线程数量
    private static final int CORE_POOL_SIZE = CPU_COUNT+1;
    //线程池中最大线程数量
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT*2 +1;
    //线程池中空闲多长时间的线程会被回收(10L代表时间无限长，即永远也不会被回收)
    private static final long KEEP_ALIVE = 10L;

    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        //最大内存
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        //分配的最大内存
        int cacheSize = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCahceDir(mContext, "bitmap");
        //如果该文件夹不存在则创建新的文件夹
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdir();
        }
        //判断剩余空间是否大于要创建的磁盘缓存区的最大空间
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                //同通过查询这个标志位可以知道DiskLruCache是否已经创建
                ismDiskLruCacheIsCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }
    //线程工厂类，负责为线程池创造线程
    private static final ThreadFactory mThreadFactory = new ThreadFactory() {
        private final AtomicInteger mConut = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r,"ImageLoader#"+mConut.getAndIncrement());
        }
    };
    //线程池，负责执行所有的异步图片加载操作
    public static final Executor THREAD_POOL_EXCUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,MAXIMUM_POOL_SIZE,KEEP_ALIVE, TimeUnit.SECONDS,new LinkedBlockingDeque<Runnable>(),mThreadFactory);
    //负责在当前线程处理其他线程中的loadimage的task
    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String url = (String) imageView.getTag(TAG_KEY_URL);
            //为了防止加载错位的问题，每次加载图片完成后，需要判断请求的图片的url和异步获取到的图片的url是否一致
            if (url.equals(result.url)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG,"The bitmap we get is not wo request,Igonre to set bitmap");
            }
        }
    };
    private void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URL, url);

        Bitmap bitmap = loadBitmapFromLruCache(url);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bitmap = loadBitmap(url, reqWidth, reqHeight);
                    if (bitmap != null) {
                        LoaderResult mLoderResult = new LoaderResult(imageView,url,bitmap);
                        mMainHandler.obtainMessage(MESSAGE_POST_RESULT,mLoderResult).sendToTarget();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        THREAD_POOL_EXCUTOR.execute(loadBitmapTask);

    }

    //将图片存入LruCache
    private void addBitmapToLruCache(String key, Bitmap bitmap) {
        //存入缓存前先判断，缓存中是否已经存在该key
        if (getBitmapFromLruCache(key) == null) {
            mLruCache.put(key, bitmap);
            return;
        }
        Log.e(TAG, "bitmap already exist!!!");
    }

    //通过LruCache获取bitmap
    private Bitmap getBitmapFromLruCache(String key) {
        //通过key获取存入的bitmap
        return mLruCache.get(key);
    }

    //通过LruCache获取bitmap
    private Bitmap loadBitmapFromLruCache(String url) {
        final String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromLruCache(key);
        return bitmap;
    }

    //通过DiskLruCache获取bitmap
    private Bitmap loadBitmapFromDiskLurCache(String url, int reqWidth, int reqHeight) {
        //这个方法不能在主线程使用(耗时比较大)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "can't load bitmap from disk lurcache in UI Thread");
            return null;
        }
        //判断DiskLurCache是否已经创建
        if (!ismDiskLruCacheIsCreated || mDiskLruCache == null) {
            Log.w(TAG, "DiskLruCache is null,please create it!!!");
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        try {
            DiskLruCache.Snapshot mSnapshot = mDiskLruCache.get(key);
            if (mSnapshot != null) {
                FileInputStream fis = (FileInputStream) mSnapshot.getInputStream(DISK_CACHE_INDEX);
                FileDescriptor fd = fis.getFD();
                //为什么要用decodeFileDescriptor()来获取bitmap？
                //
                bitmap = decodeBitmapFromFileDescriptor(fd, reqWidth, reqHeight);
                if (bitmap != null) {
                    addBitmapToLruCache(key, bitmap);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    //通过网络获取bitmap
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) {
        //判断当前是否是主线程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "can't visit network in UI Thread");
            return null;
        }
        //由于从网上下载下来的图片要线放在sd卡中，所以要判断DiskLurCache是否已经创建
        if (!ismDiskLruCacheIsCreated || mDiskLruCache == null) {
            Log.w(TAG, "DiskLruCache is null,please create it!!!");
            return null;
        }

        String key = hashKeyFromUrl(url);

        try {
            //DiskLruCache的缓存添加是通过Editor来操作的
            DiskLruCache.Editor editor = mDiskLruCache.edit(key);

            if (editor != null) {
                OutputStream os = editor.newOutputStream(DISK_CACHE_INDEX);
                //将文件写入editor的输出流中之后，文件并没有真正的被写入文件系统
                //还需要通过commit方法来提交写操作
                if (downloadUrlToStream(url, os)) {
                    editor.commit();
                } else {
                    //如果下载失败还可以通过abort方法来撤销
                    editor.abort();
                }
                //最后flush，将内存中的文件保存下来
                mDiskLruCache.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        //从网上下载的文件就可以在DiskLurCache中获得
        return loadBitmapFromDiskLurCache(url, reqWidth, reqHeight);
    }

    //从网络中下载bitmap到stream中
    private boolean downloadUrlToStream(String urlStr, OutputStream outputStream) throws IOException {

        HttpURLConnection urlConnection = null;
        BufferedOutputStream bos = null;
        BufferedInputStream bis = null;

        try {
            final URL url = new URL(urlStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            //输入流
            bis = new BufferedInputStream(urlConnection.getInputStream());
            //输出流
            bos = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int tmp;
            while ((tmp = bis.read()) != -1) {
                bos.write(tmp);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            //用完这些资源之后记得释放
            if (urlConnection != null)
                urlConnection.disconnect();
            if (bis != null)
                bis.close();
            if (bos != null)
                bos.close();

        }
        return false;
    }

    //直接从网上下载下来bitmap
    private Bitmap downloadBitmapFromUrl(String urlStr) throws IOException {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream bis = null;

        final URL url;
        try {
            url = new URL(urlStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            bis = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);

            bitmap = BitmapFactory.decodeStream(bis);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null)
                urlConnection.disconnect();
            if (bis != null)
                bis.close();
        }
        return bitmap;
    }

    //load bitmap
    private Bitmap loadBitmap(String url, int reqWidth, int reqHeight) throws IOException {
        Bitmap bitmap = null;
        //首先尝试从LurCache中获取bitmap
        bitmap = loadBitmapFromLruCache(url);
        if (bitmap != null) {
            Log.i(TAG, "load bitmap form LurCache:" + url);
            return bitmap;
        }
        //尝试从DiskLurCache中获取bitmap
        bitmap = loadBitmapFromDiskLurCache(url, reqWidth, reqHeight);
        if (bitmap != null) {
            Log.i(TAG, "load bitmap from DiskLurCache:" + url);
            return bitmap;
        }
        //尝试从网络下载后再从DiskLurCache中获取bitmap
        bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);
        if (bitmap != null) {
            Log.i(TAG, "load bitmap from Http:" + url);
            return bitmap;
        }
        //最后尝试直接从网上下载获取到bitmap(没有创建DiskLruCache)
        if (bitmap == null && !ismDiskLruCacheIsCreated) {
            Log.i(TAG, "load bitmap from network directly:" + url);
            bitmap = downloadBitmapFromUrl(url);
        }
        return bitmap;
    }

    private Bitmap decodeBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight) {

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        //由于设置了inJustDecodeBounds为true下面这个decodeFileDescriptor仅仅是获取了
        //图片的大小（是一个轻量级的操作）
        //而第二次的decodeFileDescriptor才是获取图片，由于两级操作影响了文件流中的位置属性
        //所以这里只能用decodeFileDescriptor来获取bitmap
        BitmapFactory.decodeFileDescriptor(fd, null, options);
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int mReqWidth, int mReqHeight) {
        //原始图片的宽高
        int width = options.outWidth;
        int height = options.outHeight;

        int inSampleSize = 1;
        //原始大小大于我们所需要的图片的大小就进行图片的缩小
        if (height > mReqHeight || width > mReqHeight) {
            final int halfWidth = width / 2;
            final int halfHeight = height / 2;
            //缩小后的宽高都符合要求才能确定缩放
            while ((halfHeight / inSampleSize) > mReqHeight && (halfHeight / inSampleSize) > mReqHeight) {
                //缩放的大小最好为2的指数
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private File getDiskCahceDir(Context context, String uniqueName) {
        //获取the current state of the primary "external" storage device.
        boolean ExternalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (ExternalStorageAvailable) {
            //通过传入的context获取ExternalCacheDir的地址
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        //File.separatorjava的文件分隔符，可以适应不同的平台 linux下为'/'windows下为'\'
        return new File(cachePath + File.separator + uniqueName);
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path) {
        //判断sdk版本是否大于9（android 2.3）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        //小于9获取可用空间的方法
        final StatFs statFs = new StatFs(path.getPath());
        return statFs.getBlockSize() * statFs.getAvailableBlocks();
    }

    private String hashKeyFromUrl(String url) {
        String cacheKey;
        //java.security.MessageDigest类用于为应用程序提供信息摘要算法的功能，如 MD5 或 SHA 算法。
        // 简单点说就是用于生成散列码。信息摘要是安全的单向哈希函数，它接收任意大小的数据，输出固定长度的哈希值。
        final MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            //如果失败则直接用url的hashCode充当cacheKey
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sout = new StringBuilder();
        for (byte aByte : bytes) {
            //因为byte占一个字节，而int占4个字节，所以需要与0xff相与
            String hex = Integer.toHexString(0xFF & aByte);
            //长度小于二说明该byte为0，hex为空，因此需要多加上一个0，否则这个0将丢失
            if (hex.length() < 2) {
                sout.append('0');
            }
            sout.append(hex);
        }
        return sout.toString();
    }

    private static class LoaderResult {
        public ImageView imageView;
        public String url;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String url, Bitmap bitmap) {
            this.bitmap = bitmap;
            this.imageView = imageView;
            this.url = url;
        }
    }
}
