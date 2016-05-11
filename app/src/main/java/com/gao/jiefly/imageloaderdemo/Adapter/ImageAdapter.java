package com.gao.jiefly.imageloaderdemo.Adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.gao.jiefly.imageloaderdemo.Loader.ImageLoader;
import com.gao.jiefly.imageloaderdemo.R;
import com.gao.jiefly.imageloaderdemo.View.SquareImageView;

import java.util.List;

/**
 * Created by jiefly on 2016/5/10.
 * Fighting_jiiiiie
 */
public class ImageAdapter extends BaseAdapter{
    public  boolean isScoll = false;
    private static int mImageWidth;
    private List<String> urlList;
    private LayoutInflater mLayoutInflater;
    private Drawable defaultDrawable;
    private ImageLoader mImageLoader = null;

    public ImageAdapter(List<String> urlList, Context context, ImageLoader imageLoader, int imageWidth) {
        this.urlList = urlList;
        mImageLoader = imageLoader;
        mImageWidth = imageWidth;
        mLayoutInflater = LayoutInflater.from(context);
        defaultDrawable = context.getResources().getDrawable(R.drawable.image_default);
    }

    @Override
    public int getCount() {
        return urlList.size();
    }

    @Override
    public Object getItem(int position) {
        return urlList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder viewHolder = null;
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(R.layout.gridview_item, parent, false);
            viewHolder = new ViewHolder();
            viewHolder.mImageView = (SquareImageView) convertView.findViewById(R.id.image);
           /* viewHolder.mImageView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return false;
                }
            });*/
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        SquareImageView imageView = viewHolder.mImageView;

        final String tag = (String) imageView.getTag();
        final String url = (String) getItem(position);

        if (!url.equals(tag)) {
            imageView.setImageDrawable(defaultDrawable);
        }
        //changeScoll();
        Log.i("jiefly", "set bitmap ,the flag is:" + isScoll);
        if (isScoll) {
            imageView.setTag(url);
            mImageLoader.bindBitmap(url, imageView, mImageWidth, mImageWidth);

        }
        return convertView;
    }

    private static class ViewHolder {
        public SquareImageView mImageView;
    }

}
