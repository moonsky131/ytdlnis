package com.deniscerri.ytdl.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.FormatViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.util.Extensions.loadThumbnail
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.FormatUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickDownloadBottomSheetDialog : BottomSheetDialogFragment() {

    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var formatViewModel: FormatViewModel
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var result: ResultItem
    private var currentDownloadItem: DownloadItem? = null
    private var disableUpdateData: Boolean = false

    private lateinit var thumbView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var loadingContainer: View
    private lateinit var audioRecycler: RecyclerView
    private lateinit var videoRecycler: RecyclerView
    private lateinit var audioAdapter: QuickFormatAdapter
    private lateinit var videoAdapter: QuickFormatAdapter

    private lateinit var genericVideoFormats: MutableList<Format>
    private lateinit var genericAudioFormats: MutableList<Format>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        val dwl = downloadCardViewModel.downloadItem

        disableUpdateData = arguments?.getBoolean("disableUpdateData") == true

        if (res == null) {
            dismiss()
            return
        }
        result = res
        currentDownloadItem = dwl

        val formatUtil = FormatUtil(requireContext())
        genericVideoFormats = formatUtil.getGenericVideoFormats(requireContext().resources)
        genericAudioFormats = formatUtil.getGenericAudioFormats(requireContext().resources)
    }

    @SuppressLint("RestrictedApi", "InflateParams")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_quick_download_snaptube, null)
        dialog.setContentView(view)
        dialog.window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())

        dialog.setOnShowListener {
            runCatching {
                (view.parent as? View)?.let { parentView ->
                    val behavior = BottomSheetBehavior.from(parentView)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    val displayMetrics = DisplayMetrics()
                    activity?.windowManager?.defaultDisplay?.getMetrics(displayMetrics)
                    if (displayMetrics.heightPixels > 0) {
                        behavior.peekHeight = displayMetrics.heightPixels
                    }
                }
            }
        }

        thumbView = view.findViewById(R.id.quick_media_thumb)
        titleView = view.findViewById(R.id.quick_media_title)
        subtitleView = view.findViewById(R.id.quick_media_subtitle)
        loadingContainer = view.findViewById(R.id.quick_loading_container)
        audioRecycler = view.findViewById(R.id.quick_audio_recycler)
        videoRecycler = view.findViewById(R.id.quick_video_recycler)

        audioRecycler.layoutManager = GridLayoutManager(requireContext(), 2)
        videoRecycler.layoutManager = GridLayoutManager(requireContext(), 2)

        audioAdapter = QuickFormatAdapter { option ->
            handleOptionSelected(option)
        }
        videoAdapter = QuickFormatAdapter { option ->
            handleOptionSelected(option)
        }

        audioRecycler.adapter = audioAdapter
        videoRecycler.adapter = videoAdapter

        updateHeaderUI()
        refreshFormatOptions()

        view.findViewById<MaterialButton>(R.id.quick_close_button).setOnClickListener {
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.quick_advanced_button).setOnClickListener {
            openAdvancedDialog()
        }

        // Trigger updates if necessary
        if (!disableUpdateData) {
            if (result.title.isEmpty() && currentDownloadItem == null) {
                initUpdateData()
            } else {
                val usingGenericFormatsOrEmpty = result.formats.isEmpty() || result.formats.any { it.format_note.contains("ytdlnisgeneric") }
                if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", false)) {
                    initUpdateFormats(result)
                }
            }
        }

        observeViewModel()
    }

    private fun updateHeaderUI() {
        titleView.text = result.title.ifEmpty { result.url }
        val authorText = result.author.ifEmpty { "" }
        val durationText = result.duration.ifEmpty { "" }

        subtitleView.text = when {
            authorText.isNotEmpty() && durationText.isNotEmpty() -> "$authorText • $durationText"
            authorText.isNotEmpty() -> authorText
            durationText.isNotEmpty() -> durationText
            else -> result.website
        }

        if (result.thumb.isNotBlank()) {
            thumbView.loadThumbnail(false, result.thumb)
        }
    }

    private fun refreshFormatOptions() {
        val audioOptions = generateAudioOptions(result)
        val videoOptions = generateVideoOptions(result)

        audioAdapter.submitList(audioOptions)
        videoAdapter.submitList(videoOptions)
    }

    private fun parseDurationStringToSeconds(durationStr: String): Long {
        val parts = durationStr.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun generateAudioOptions(res: ResultItem): List<QuickFormatOption> {
        val durationSeconds = parseDurationStringToSeconds(res.duration)
        val options = mutableListOf<QuickFormatOption>()

        val audioStreams = res.formats.filter {
            it.format_note.contains("audio", ignoreCase = true) || (it.vcodec.isBlank() || it.vcodec == "none")
        }

        val bestAudioStream = audioStreams.maxByOrNull { it.filesize }

        // Find native M4A/AAC stream (e.g. YouTube format 140) to allow instant download with ZERO conversion
        val nativeM4aStream = audioStreams.firstOrNull {
            it.container.equals("m4a", ignoreCase = true) ||
            it.acodec.contains("mp4a", ignoreCase = true) ||
            it.acodec.contains("aac", ignoreCase = true)
        } ?: bestAudioStream

        val size320 = if (durationSeconds > 0) {
            FileUtil.convertFileSize(durationSeconds * 320 * 1000L / 8)
        } else {
            "~8-12 MB"
        }

        val size160 = if (durationSeconds > 0) {
            FileUtil.convertFileSize(durationSeconds * 160 * 1000L / 8)
        } else {
            "~4-6 MB"
        }

        val size128 = if (durationSeconds > 0) {
            FileUtil.convertFileSize(durationSeconds * 128 * 1000L / 8)
        } else {
            "~3-5 MB"
        }

        val sizeM4a = if (nativeM4aStream != null && nativeM4aStream.filesize > 0) {
            FileUtil.convertFileSize(nativeM4aStream.filesize)
        } else if (durationSeconds > 0) {
            FileUtil.convertFileSize(durationSeconds * 128 * 1000L / 8)
        } else {
            "~4-8 MB"
        }

        // 1. M4A Original (Fastest - Direct stream copy, no re-encoding)
        options.add(
            QuickFormatOption(
                id = "m4a_best",
                title = "M4A Original",
                badge = getString(R.string.badge_fast),
                size = sizeM4a,
                ext = "M4A",
                isAudio = true,
                format = nativeM4aStream,
                audioBitrate = "best",
                container = "m4a"
            )
        )

        // 2. MP3 Options
        options.add(
            QuickFormatOption(
                id = "mp3_320k",
                title = "MP3 320k",
                badge = getString(R.string.badge_hq),
                size = size320,
                ext = "MP3",
                isAudio = true,
                format = bestAudioStream,
                audioBitrate = "320K",
                container = "mp3"
            )
        )

        options.add(
            QuickFormatOption(
                id = "mp3_160k",
                title = "MP3 160k",
                badge = null,
                size = size160,
                ext = "MP3",
                isAudio = true,
                format = bestAudioStream,
                audioBitrate = "160K",
                container = "mp3"
            )
        )

        options.add(
            QuickFormatOption(
                id = "mp3_128k",
                title = "MP3 128k",
                badge = null,
                size = size128,
                ext = "MP3",
                isAudio = true,
                format = bestAudioStream,
                audioBitrate = "128K",
                container = "mp3"
            )
        )

        return options
    }

    private fun generateVideoOptions(res: ResultItem): List<QuickFormatOption> {
        val durationSeconds = parseDurationStringToSeconds(res.duration)
        val options = mutableListOf<QuickFormatOption>()

        val videoStreams = res.formats.filter {
            it.vcodec.isNotBlank() && it.vcodec != "none" && !it.format_note.contains("audio", ignoreCase = true)
        }

        val bestAudioSize = res.formats.filter {
            it.format_note.contains("audio", ignoreCase = true) || (it.vcodec.isBlank() || it.vcodec == "none")
        }.map { it.filesize }.maxOrNull() ?: 0L

        if (videoStreams.isNotEmpty()) {
            val groupedByResolution = videoStreams
                .filter { (it.height ?: 0) > 0 }
                .groupBy { it.height!! }
                .toSortedMap(compareByDescending { it })

            for ((height, formatsForHeight) in groupedByResolution) {
                val chosenFormat = formatsForHeight.firstOrNull { it.container.equals("mp4", ignoreCase = true) }
                    ?: formatsForHeight.first()

                val badge = when {
                    height >= 2160 -> "4K"
                    height >= 1440 -> "2K"
                    height >= 1080 -> getString(R.string.badge_fhd)
                    height >= 720 -> getString(R.string.badge_hd)
                    else -> null
                }

                var totalSize = chosenFormat.filesize
                if (totalSize > 0 && bestAudioSize > 0 && (chosenFormat.acodec.isBlank() || chosenFormat.acodec == "none")) {
                    totalSize += bestAudioSize
                }

                val sizeStr = if (totalSize > 0) {
                    FileUtil.convertFileSize(totalSize)
                } else if (durationSeconds > 0) {
                    val approxBitrateKbps = when {
                        height >= 2160 -> 18000L
                        height >= 1440 -> 9000L
                        height >= 1080 -> 4500L
                        height >= 720 -> 2500L
                        height >= 480 -> 1200L
                        else -> 700L
                    }
                    "~" + FileUtil.convertFileSize(durationSeconds * approxBitrateKbps * 1000L / 8)
                } else {
                    "?"
                }

                val container = if (chosenFormat.container.isNotBlank()) chosenFormat.container.lowercase() else "mp4"

                options.add(
                    QuickFormatOption(
                        id = "video_${height}p",
                        title = "${height}p",
                        badge = badge,
                        size = sizeStr,
                        ext = container.uppercase(),
                        isAudio = false,
                        format = chosenFormat,
                        videoResolution = height.toString(),
                        container = container
                    )
                )
            }
        }

        if (options.isEmpty()) {
            val standardResolutions = listOf(
                Triple(1080, getString(R.string.badge_fhd), 4500L),
                Triple(720, getString(R.string.badge_hd), 2500L),
                Triple(480, null, 1200L),
                Triple(360, null, 700L)
            )

            for ((height, badge, bitrateKbps) in standardResolutions) {
                val sizeStr = if (durationSeconds > 0) {
                    "~" + FileUtil.convertFileSize(durationSeconds * bitrateKbps * 1000L / 8)
                } else {
                    "?"
                }

                options.add(
                    QuickFormatOption(
                        id = "video_${height}p",
                        title = "${height}p",
                        badge = badge,
                        size = sizeStr,
                        ext = "MP4",
                        isAudio = false,
                        videoResolution = height.toString(),
                        container = "mp4"
                    )
                )
            }
        }

        return options
    }

    private fun handleOptionSelected(option: QuickFormatOption) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            resultViewModel.cancelUpdateFormatsItemData()

            runCatching {
                val toastMsg = getString(R.string.downloading_format, result.title.ifEmpty { result.url }, option.title)
                Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
            }

            val downloadItem: DownloadItem = if (option.isAudio) {
                val item = downloadViewModel.createDownloadItemFromResult(result, givenType = DownloadType.audio)
                item.container = option.container
                item.audioPreferences.bitrate = option.audioBitrate ?: "best"
                if (option.format != null) {
                    item.format = option.format
                }
                item
            } else {
                val item = downloadViewModel.createDownloadItemFromResult(result, givenType = DownloadType.video)
                item.container = option.container
                if (option.format != null) {
                    item.format = option.format
                    val preferredAudio = downloadViewModel.getPreferredAudioFormats(result.formats)
                    item.videoPreferences.audioFormatIDs.clear()
                    item.videoPreferences.audioFormatIDs.addAll(preferredAudio)
                } else if (option.videoResolution != null) {
                    val matched = genericVideoFormats.firstOrNull { it.format_note.contains(option.videoResolution) }
                    if (matched != null) {
                        item.format = matched
                    }
                }
                item
            }

            withContext(Dispatchers.IO) {
                downloadViewModel.queueDownloads(listOf(downloadItem), ignoreDuplicates = false)
            }

            runCatching { dismiss() }
            if (activity is ShareActivity) {
                activity?.finish()
            }
        }
    }

    private fun openAdvancedDialog() {
        val bundle = Bundle()
        bundle.putSerializable("type", DownloadType.video)
        bundle.putBoolean("disableUpdateData", false)
        bundle.putBoolean("ignore_duplicates", false)
        runCatching {
            findNavController().navigate(R.id.action_quickDownloadBottomSheetDialog_to_downloadBottomSheetDialog, bundle)
        }.onFailure {
            dismiss()
            val detailedDialog = DownloadBottomSheetDialog()
            detailedDialog.arguments = bundle
            detailedDialog.show(parentFragmentManager, "DownloadBottomSheetDialog")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest { isUpdating ->
                loadingContainer.visibility = if (isUpdating) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { resList ->
                if (resList.isNullOrEmpty()) return@collectLatest
                if (resList.size > 1) {
                    if (activity is ShareActivity) {
                        runCatching {
                            findNavController().navigate(
                                R.id.action_quickDownloadBottomSheetDialog_to_selectPlaylistItemsDialog,
                                bundleOf(Pair("resultIDs", resList.map { it!!.id }.toLongArray()))
                            )
                        }
                    } else {
                        dismiss()
                    }
                    return@collectLatest
                }
                val updatedResult = resList[0] ?: return@collectLatest
                result = updatedResult
                downloadCardViewModel.setResultItem(result)
                updateHeaderUI()
                refreshFormatOptions()

                val usingGenericFormatsOrEmpty = result.formats.isEmpty() || result.formats.any { it.format_note.contains("ytdlnisgeneric") }
                if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", false)) {
                    initUpdateFormats(result)
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats == null) return@collectLatest
                result.formats = formats
                downloadCardViewModel.setResultItem(result)
                refreshFormatOptions()
            }
        }
    }

    private fun initUpdateData() {
        runCatching {
            if (result.url.isBlank()) {
                dismiss()
                return
            }
            if (resultViewModel.updatingData.value) return
            lifecycleScope.launch(Dispatchers.IO) {
                resultViewModel.updateItemData(result)
            }
        }
    }

    private fun initUpdateFormats(res: ResultItem) {
        runCatching {
            if (resultViewModel.updatingFormats.value) return
            CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                resultViewModel.updateFormatItemData(res)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            resultViewModel.cancelUpdateFormatsItemData()
            super.onDismiss(dialog)
        }
    }
}
