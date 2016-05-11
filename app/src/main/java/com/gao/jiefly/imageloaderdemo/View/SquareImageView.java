package com.gao.jiefly.imageloaderdemo.View;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

/**
 * Created by jiefly on 2016/5/10.
 * Fighting_jiiiiie
 */
public class SquareImageView extends ImageView {
    public SquareImageView(Context context) {
        super(context);

    }
    private boolean isDouble =true;
    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                isDouble = true;
                invalidate();
                Log.i("SquareImageView111","is double"+ isDouble);
                return false;
            }
        });
    }

    public SquareImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                isDouble = true;
                invalidate();
                Log.i("SquareImageView111","is double"+ isDouble);
                return false;
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        widthMeasureSpec = isDouble?2*widthMeasureSpec:widthMeasureSpec;
        isDouble = false;
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        Log.i("SquareImageView","is double"+ isDouble);
    }

}
