package com.hyperos.updater.ui.screens.downloads

import androidx.lifecycle.ViewModel
import com.hyperos.updater.domain.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    val downloadManager: DownloadManager
) : ViewModel()
