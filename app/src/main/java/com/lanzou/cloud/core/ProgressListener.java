package com.lanzou.cloud.core;

/** 传输进度回调 */
public interface ProgressListener {
    void onProgress(String name, long total, long now);
}
