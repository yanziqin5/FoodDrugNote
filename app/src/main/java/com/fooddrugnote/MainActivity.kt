package com.fooddrugnote

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.fooddrugnote.api.DoubaoApi
import com.fooddrugnote.databinding.ActivityMainBinding
import com.fooddrugnote.db.AppDatabase
import com.fooddrugnote.db.ScanRecord
import com.fooddrugnote.util.ImageUtil
import com.fooddrugnote.util.PermissionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.io.File
import android.content.SharedPreferences
import com.fooddrugnote.util.ImageCacheManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页面：拍照/选图 → AI分析 → 结果展示 → 历史记录
 *
 * 功能清单：
 * 1. 相机拍照、相册选图
 * 2. 图片压缩后上传豆包多模态API分析药品忌口
 * 3. 风险等级文本高亮（高风险红、低风险橙）
 * 4. Room本地缓存扫描历史，7天内相同药品命中缓存不重复请求
 * 5. 启动免责声明弹窗
 * 6. 清空历史记录
 */
class MainActivity : AppCompatActivity() {
    companion object {
        // 新增合并进来的Diff回调
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ScanRecord>() {
            override fun areItemsTheSame(oldItem: ScanRecord, newItem: ScanRecord): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ScanRecord, newItem: ScanRecord): Boolean {
                return oldItem == newItem
            }
        }

        // 你原来就存在的常量，保留原样不动
        private const val HASH_THRESHOLD = 10
        private const val CACHE_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEY_LAST_PREVIEW = "last_preview_path"

        // 风险文本高亮正则：仅编译一次，避免每次展示/历史绑定都重复编译（减少 GC 压力）
        private val HIGH_RISK_PATTERN = Regex("【高风险】")
        private val LOW_RISK_PATTERN = Regex("【低风险】")

        // 风险兜底关键词：提出为常量，避免每次定级都重新分配 List（历史列表逐项调用时尤其明显）
        private val HIGH_RISK_KEYWORDS = listOf(
            "禁止", "严禁", "杜绝", "切勿", "绝对不可", "绝对不能", "不可", "忌用",
            "致死", "中毒", "过敏性休克", "肝肾损伤", "慎用", "避免"
        )
        private val LOW_RISK_KEYWORDS = listOf(
            "适量", "少量", "不宜", "谨慎", "注意", "尽量", "少"
        )
    }

    // 下面你所有原有代码全部原样保留，不改动
    private lateinit var binding: ActivityMainBinding
    private val doubaoApi by lazy {
        DoubaoApi(apiKey = BuildConfig.DOUBAO_API_KEY)
    }

    // 数据库
    private val db by lazy { AppDatabase.getInstance(this) }
    private val dao by lazy { db.scanRecordDao() }

    // 历史列表适配器
    private lateinit var historyAdapter: HistoryAdapter

    // 拍照相关
    private var photoFile: File? = null

    // 当前预览图片的本地绝对路径（相机：Pictures 目录；相册：image_cache 拷贝）。
    // App 重建时直接读取该本地文件恢复预览/分析，不再依赖 content:// Uri。
    private var currentImagePath: String? = null

    // 当前已加载到内存的压缩 Bitmap
    private var currentBitmap: android.graphics.Bitmap? = null

    // 已写入数据库的图片路径集合（用于判断某缓存文件是否"已转正"，避免误删）
    private var savedPaths: Set<String> = emptySet()

    // 共享偏好（保存免责声明勾选、上次预览路径）
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
    }

    // 退出时清理缓存用的独立作用域（不随 lifecycle 取消，确保 best-effort 执行）。
    // 附加 CoroutineExceptionHandler：清理属 best-effort，若文件操作意外抛异常应静默忽略，
    // 否则未捕获异常会冒泡到 Android 默认协程异常处理器并直接杀掉进程（表现为 Channel is unrecoverably broken）。
    private val cleanupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )

    // 是否正在分析（用于避免分析途中重新选图造成 Bitmap 竞态/内存泄漏）
    private var isAnalyzing = false

    // 是否正在加载/解图（禁用选图按钮，避免快速重复点击触发并发加载导致 Bitmap 竞争）
    private var isLoadingImage = false

    // 拍照启动器
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val path = photoFile?.absolutePath
        if (success && path != null) {
            // 拍照成功：先清理上一张未"转正"的预览（仅删孤立文件，保留历史记录图片）
            discardPreviousPreview()
            currentImagePath = path
            prefs.edit().putString(KEY_LAST_PREVIEW, path).apply()
            loadAndCompressImage(path)
        } else {
            // 用户取消拍照：清理未使用的临时文件，避免 Pictures 目录残留
            photoFile?.delete()
            photoFile = null
            currentImagePath = null
            prefs.edit().remove(KEY_LAST_PREVIEW).apply()
        }
    }

    // 相册选图启动器
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 将 content:// 相册图片拷贝到应用私有 image_cache 目录，生成唯一文件名
                    val path = ImageCacheManager.copyUriToCache(this@MainActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (path != null) {
                            // 切换图片：清理上一张未"转正"的缓存文件，避免残留
                            discardPreviousPreview()
                            currentImagePath = path
                            prefs.edit().putString(KEY_LAST_PREVIEW, path).apply()
                            loadAndCompressImage(path)
                        } else {
                            Toast.makeText(this@MainActivity, "图片加载失败", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "图片加载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            // 初始化权限工具
            PermissionUtil.init(this)

            // 启动免责弹窗
            showDisclaimerDialog()

            // 初始化历史列表
            initHistoryList()

            // 按钮点击事件
            binding.btnTakePhoto.setOnClickListener {
                PermissionUtil.requestCameraPermission { openCamera() }
            }
            binding.btnAlbum.setOnClickListener {
                PermissionUtil.requestStoragePermission { openAlbum() }
            }
            binding.btnAnalyze.setOnClickListener { startAnalyze() }
            binding.btnClearHistory.setOnClickListener { clearHistory() }
            binding.btnCopy.setOnClickListener { copyResult() }
            binding.btnRetry.setOnClickListener { startAnalyze() }
            binding.btnQuery.setOnClickListener { queryByName() }
            binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    queryByName()
                    true
                } else false
            }

            // 启动清理：删除 7 天以上、无数据库引用的孤立缓存图片
            cleanupCacheOnStartup()

            // 重建恢复：读取上次选中的预览图（文件可能已被过期清理，加载失败则静默忽略）
            val lastPreview = prefs.getString(KEY_LAST_PREVIEW, null)
            if (lastPreview != null && File(lastPreview).exists()) {
                currentImagePath = lastPreview
                loadAndCompressImage(lastPreview, silent = true)
            }
        } catch (e: Throwable) {
            // 启动流程任何未捕获异常都显式抛出到界面，避免"图标后直接黑屏"无从排查。
            // 同时将完整堆栈打到 logcat（tag STARTUP），便于精准定位。
            android.util.Log.e("STARTUP", "onCreate 启动流程异常", e)
            try {
                AlertDialog.Builder(this)
                    .setTitle("启动失败（诊断信息）")
                    .setMessage("${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString().take(2000)}")
                    .setPositiveButton("关闭") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (_: Throwable) {
                // 对话框无法显示时的兜底方案，避免二次崩溃导致"完全无响应闪退"
                Toast.makeText(this, "应用启动失败，请重试", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ========== 拍照 & 相册 ==========

    private fun openCamera() {
        // 防止加载/分析过程中重复触发，避免并发解码导致预览与 currentBitmap 不同步、浪费资源引起卡顿
        if (isLoadingImage || isAnalyzing) return
        // createImageFile 在存储不可用/空间不足时会抛 IOException，
        // 必须就地捕获，否则点"拍照"会直接抛出未处理异常导致崩溃
        val file = try {
            ImageUtil.createImageFile(this)
        } catch (e: Exception) {
            Toast.makeText(this, "无法创建图片文件，请检查存储空间", Toast.LENGTH_SHORT).show()
            return
        }
        photoFile = file
        // getUriForFile / launch 也可能因个别机型 FileProvider 配置异常（如 authority 不匹配）抛异常，
        // 必须就地捕获，否则点"拍照"会直接崩溃。捕获后清理 photoFile 并提示，避免进入拍照失败态。
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            photoFile = null
            Toast.makeText(this, "无法打开相机，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAlbum() {
        // 防止加载/分析过程中重复触发，避免并发解码导致预览与 currentBitmap 不同步、浪费资源引起卡顿
        if (isLoadingImage || isAnalyzing) return
        pickImageLauncher.launch("image/*")
    }

    /**
     * 加载并压缩图片，显示到预览区。
     * @param path 本地图片绝对路径（相机 Pictures 目录 或 相册 image_cache 拷贝）
     * @param silent 为 true 时加载失败不弹 Toast（用于重建恢复预览）
     */
    private fun loadAndCompressImage(path: String, silent: Boolean = false) {
        // 重入保护：加载中或分析中再次调用直接丢弃，避免并发解码竞争 currentBitmap，
        // 也避免快速连点造成的重复解码与界面闪烁/卡顿
        if (isLoadingImage || isAnalyzing) return
        // 函数体作用域的 this 即 MainActivity（Context）。协程块内 this 会变成 CoroutineScope，
        // 因此在此捕获 context，协程内统一用 context，避免使用 this@MainActivity 冗余标签。
        val context = this
        // 捕获 launch 返回的 Job：本编译器版本下带标签的 lambda 无法推导 CoroutineScope
        // 接收者，故 isActive 扩展属性不可用；改用闭包变量 job（先声明可空 var 再赋值，
        // 避免未初始化即被 lambda 捕获），用 Job.isActive 成员判断，语义与原 isActive 一致。
        var job: Job? = null
        job = lifecycleScope.launch(block = labelLoad@ {
            // 进入加载态：禁用选图/拍照/分析按钮，避免快速重复点击触发并发加载，
            // 否则两个解码协程可能在主线程块竞争 currentBitmap，导致上一帧 Bitmap 被提前回收、
            // 而正在进行的分析仍持有该 Bitmap 引用并访问已回收对象而崩溃。
            isLoadingImage = true
            setImageButtonsEnabled(false)
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageUtil.uriToCompressedBitmap(context, Uri.fromFile(File(path)))
                }
                if (bitmap == null) {
                    // 解码失败：静默或提示，直接结束避免后续空指针
                    if (!silent) {
                        Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
                    }
                    return@labelLoad
                }
                // 加载过程中 Activity 已被销毁/协程被取消：回收位图，避免内存泄漏与 OOM 风险
                if (job?.isActive == false) {
                    bitmap.recycle()
                    return@labelLoad
                }
                // 非分析态下释放上一张 Bitmap，避免重复选图造成内存泄漏
                val old = currentBitmap
                if (old != null && old !== bitmap && !isAnalyzing) {
                    old.recycle()
                }
                currentBitmap = bitmap
                // 预览加载原始文件（相机原图可能 4000px+），必须限制解码尺寸与像素格式，
                // 否则每次选图都会做一次全分辨率解码，低端机易卡顿/内存峰值过高
                // 绑到具体 View（而非 Activity）以跟随视图生命周期，降低"已销毁 Activity 上启动加载"异常风险
                Glide.with(binding.ivPreview).load(File(path))
                    .override(1280, 1280)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .into(binding.ivPreview)
                binding.tvHint.visibility = View.GONE
                // 分析进行中不要重新启用分析按钮，避免双分析
                binding.btnAnalyze.isEnabled = !isAnalyzing
                binding.btnRetry.visibility = View.GONE
                // 清空上一次分析结果/风险徽章，避免旧状态残留、或错误态的"重试"按钮误留
                binding.tvResult.text = ""
                binding.tvResult.visibility = View.GONE
                binding.tvRiskBadge.visibility = View.GONE
                binding.btnCopy.isEnabled = false
            } catch (e: Exception) {
                // 协程取消原样抛出；其余异常兜底防止未捕获异常导致进程崩溃（如 OOM、罕见 IO 错误等）
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("IMAGE", "加载图片失败", e)
                if (!silent) {
                    Toast.makeText(context, "图片加载失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoadingImage = false
                // 协程正常完成时恢复"拍照/选图/分析"按钮可用态；
                // 被取消说明 Activity 已销毁、binding 不可用，必须跳过，否则访问已销毁视图会崩溃。
                // 注意：已完成的 Job.isActive == false，因此不能用 isActive 判断，必须用 isCancelled。
                if (job?.isCancelled != true) setImageButtonsEnabled(!isAnalyzing)
            }
        })
    }

    /** 批量开关"拍照/选图/分析"按钮，防止加载或分析期间的并发操作引发状态错乱 */
    private fun setImageButtonsEnabled(enabled: Boolean) {
        binding.btnTakePhoto.isEnabled = enabled
        binding.btnAlbum.isEnabled = enabled
        binding.btnAnalyze.isEnabled = enabled && !isAnalyzing
    }

    // ========== AI 分析 ==========

    /** 统一结果展示：图片分析与名称查询共用 */
    private fun showResult(result: String, isError: Boolean, showRetry: Boolean = true) {
        binding.progressBar.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        binding.btnTakePhoto.isEnabled = true
        binding.btnAlbum.isEnabled = true
        binding.btnClearHistory.isEnabled = true
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = highlightRiskText(result)
        updateRiskBadge(result)
        binding.btnRetry.visibility = if (isError && showRetry) View.VISIBLE else View.GONE
        binding.btnCopy.isEnabled = !isError
        isAnalyzing = false
    }

    private fun startAnalyze() {
        val bitmap = currentBitmap ?: run {
            Toast.makeText(this, R.string.hint_no_image, Toast.LENGTH_SHORT).show()
            return
        }
        // 防止分析进行中重复触发（按钮可能在选图后被重新启用）
        if (isAnalyzing) return

        // 抓取当前图片路径（选图/拍照时已经落盘的本地路径），
        // 用本地路径而非实时读 photoFile，避免分析途中用户再次拍照导致路径被覆盖错乱
        val capturedImagePath = currentImagePath

        isAnalyzing = true
        // 分析期间禁用所有可能破坏上下文的按钮：拍照/选图会触发 discardPreviousPreview() 删除
        // 正在分析的图片文件，导致数据库写入空路径；清空历史也会与插入操作产生竞态
        binding.btnAnalyze.isEnabled = false
        binding.btnTakePhoto.isEnabled = false
        binding.btnAlbum.isEnabled = false
        binding.btnClearHistory.isEnabled = false
        binding.btnRetry.visibility = View.GONE
        binding.tvResult.text = getString(R.string.analyzing)
        // 重新选图会把 tvResult 隐藏，这里恢复可见，确保"分析中"与分析结论能正常显示
        binding.tvResult.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            var result: String? = null
            var savedPath: String? = null
            var isError = false
            try {
                // 1. 计算图片感知哈希，先尝试命中 7 天内的本地缓存，避免重复请求
                val imageHash = ImageUtil.perceptualHash(bitmap)
                val expireTime = System.currentTimeMillis() - CACHE_VALIDITY_MS
                val cached = dao.getRecent(expireTime).firstOrNull { record ->
                    record.imageHash.isNotEmpty() &&
                            ImageUtil.hammingDistance(
                                record.imageHash,
                                imageHash
                            ) <= HASH_THRESHOLD
                }

                if (cached != null) {
                    // 命中缓存：直接复用历史结果，节省 API 调用
                    result = cached.resultText
                    savedPath = cached.imagePath
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.cache_hit,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // 2. 调用豆包多模态 API 分析药盒忌口
                    result = doubaoApi.scanDrugImage(bitmap)

                    // 3. 保存图片到本地路径（历史记录回显用）。
                    // 相册/拍照在选图时已将图片落盘到本地路径，直接复用；
                    // capturedImagePath 理论上不会为空（选图即落盘），这里仅作兜底
                    savedPath = capturedImagePath ?: run {
                        val fileName = "scan_${System.currentTimeMillis()}.jpg"
                        ImageUtil.saveBitmapToFile(this@MainActivity, bitmap, fileName)
                    }

                    // 4. 判断请求是否成功；失败则不写库、保留 bitmap 以便重试
                    isError = result.contains("网络异常")
                            || result.contains("请求失败")
                            || result.contains("未获取到")
                    if (!isError) {
                        val record = ScanRecord(
                            drugKey = extractDrugKey(result),
                            imagePath = savedPath,
                            resultText = result,
                            imageHash = imageHash
                        )
                        dao.insert(record)
                    }
                }
            } catch (e: Exception) {
                // 取消信号需原样抛出，不要让取消被吞掉
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 任何未预期异常都兜底为错误态，避免 UI 卡死
                result = "分析出错：${e.message}"
                isError = true
            } finally {
                // 回收分析用 Bitmap：放在 IO 线程、UI 更新之前，
                // 这样即使分析途中 Activity 被销毁（协程取消）也能回收，避免内存泄漏；
                // 同时避免被 onDestroy 在 Bitmap 仍被分析使用时误回收而崩溃。
                if (!isError && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                withContext(Dispatchers.Main) {
                    showResult(result ?: "", isError)
                    // 仅当 currentBitmap 仍是本次分析所用的同一对象时才释放，
                    // 避免误清用户中途重新选好的图片
                    if (!isError) {
                        if (currentBitmap === bitmap) currentBitmap = null
                        // 分析成功"转正"：该图片路径已被数据库引用，
                        // 加入 savedPaths（避免后续切换时误删）并清空"当前预览"状态，
                        // 防止重建回显一张已经分析过的图
                        if (savedPath != null) {
                            savedPaths = savedPaths + savedPath
                            prefs.edit().remove(KEY_LAST_PREVIEW).apply()
                            currentImagePath = null
                        }
                    }
                }
            }
        }
    }

    /**
     * 按名称查询药/食相克（不拍照）
     */
    private fun queryByName() {
        val name = binding.etQuery.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入药品或食物名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (isAnalyzing) return

        isAnalyzing = true
        binding.btnAnalyze.isEnabled = false
        binding.btnTakePhoto.isEnabled = false
        binding.btnAlbum.isEnabled = false
        binding.btnClearHistory.isEnabled = false
        binding.btnQuery.isEnabled = false
        binding.btnRetry.visibility = View.GONE
        binding.tvResult.text = "正在查询\"$name\"的相克禁忌，请稍候..."
        binding.tvResult.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            var result: String? = null
            var isError = false
            try {
                result = doubaoApi.queryByName(name)
                isError = result.contains("网络异常")
                        || result.contains("请求失败")
                        || result.contains("未获取到")
                if (!isError) {
                    // 名称查询结果也写入历史（纯文字记录，无图片），方便回看
                    try {
                        dao.insert(ScanRecord(0, name, "", result, "", System.currentTimeMillis()))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                // 取消信号需原样抛出，避免被吞
                if (e is kotlinx.coroutines.CancellationException) throw e
                result = "查询出错：${e.message}"
                isError = true
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnQuery.isEnabled = true
                    // 文本查询不保存图片，失败也不显示"重新分析"（无图可重分析）
                    showResult(result ?: "", isError, showRetry = false)
                }
            }
        }
    }

    /**
     * 从结果文本提取药品关键词（简易：取第一行药品名，用于缓存去重）
     */
    private fun extractDrugKey(text: String): String {
        val firstLine = text.lines().firstOrNull() ?: text
        return firstLine.take(30).replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9]"), "")
    }

    /**
     * 文本高亮：【高风险】标红、【低风险】标橙
     */
    private fun highlightRiskText(text: String): SpannableString {
        val spannable = SpannableString(text)
        // 高风险红色
        HIGH_RISK_PATTERN.findAll(text).forEach { match ->
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#B54A3A")),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // 低风险橙色
        LOW_RISK_PATTERN.findAll(text).forEach { match ->
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#B57A3A")),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /** 风险级别 */
    private enum class RiskLevel { HIGH, LOW, SAFE }

    /**
     * 统一风险定级：
     * 1) 优先匹配 AI 输出的 【高风险】 / 【低风险】 标记
     * 2) 无标记时用兜底关键词扫描，避免模型没套标记却被误判为「较安全」
     */
    private fun resolveRiskLevel(text: String): RiskLevel {
        if (text.contains("【高风险】")) return RiskLevel.HIGH
        if (text.contains("【低风险】")) return RiskLevel.LOW
        if (HIGH_RISK_KEYWORDS.any { text.contains(it) }) return RiskLevel.HIGH
        if (LOW_RISK_KEYWORDS.any { text.contains(it) }) return RiskLevel.LOW
        return RiskLevel.SAFE
    }

    // 根据结果文本设置风险徽章
    private fun updateRiskBadge(text: String) {
        val badge = binding.tvRiskBadge
        // 空结果或出错态不显示徽章
        if (text.isBlank() || text.startsWith("分析出错") || text.startsWith("请求失败")) {
            badge.visibility = View.GONE
            return
        }
        when (resolveRiskLevel(text)) {
            RiskLevel.HIGH -> {
                badge.text = "高风险"
                badge.setBackgroundResource(R.drawable.bg_risk_high)
                badge.setTextColor(ContextCompat.getColor(this, R.color.high_risk_text))
                badge.visibility = View.VISIBLE
            }
            RiskLevel.LOW -> {
                badge.text = "低风险"
                badge.setBackgroundResource(R.drawable.bg_risk_low)
                badge.setTextColor(ContextCompat.getColor(this, R.color.low_risk_text))
                badge.visibility = View.VISIBLE
            }
            RiskLevel.SAFE -> {
                badge.text = "较安全"
                badge.setBackgroundResource(R.drawable.bg_risk_safe)
                badge.setTextColor(ContextCompat.getColor(this, R.color.safe_text))
                badge.visibility = View.VISIBLE
            }
        }
    }

    // ========== 历史记录 ==========

    private fun initHistoryList() {
        historyAdapter = HistoryAdapter()
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        lifecycleScope.launch {
            try {
                dao.getAllHistory().collectLatest { list ->
                    // 维护"已转正"路径集合，供缓存清理与切换删除判断使用
                    savedPaths = list.map { it.imagePath }.toSet()
                    historyAdapter.submitList(list)
                    binding.tvHistoryEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    // 同步历史计数（布局中的计数文本），避免始终显示"0"
                    binding.tvHistoryCount.text = list.size.toString()
                }
            } catch (e: Exception) {
                // 数据库读取异常（如偶发损坏）时静默忽略，绝不让未捕获异常杀掉进程
            }
        }
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("确定要清空全部查询历史记录吗？此操作不可恢复，对应图片也会一并删除。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 先收集图片路径，清空记录后再删除图片文件，避免图片残留占用空间
                        val records = dao.getAllRecords()
                        dao.clearAll()
                        records.forEach { deleteImageFile(it.imagePath) }
                    } catch (e: Exception) {
                        // 数据库操作异常时回到主线程提示，绝不让未捕获异常杀掉进程
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "清空历史失败：${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                // 清空后库已空，立即同步内存集合（避免竞态误判）
                savedPaths = emptySet()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除历史记录对应的本地图片文件（仅删除应用私有目录中的副本，不会触碰用户相册原图）
     */
    private fun deleteImageFile(path: String?) {
        if (path.isNullOrEmpty()) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // 删除失败不影响主流程
        }
    }

    /**
     * 切换/取消当前预览时，删除上一张"未转正"的本地缓存文件。
     * 已写入数据库的图片（savedPaths 中）不会被删除，避免误删历史记录图片。
     */
    private fun discardPreviousPreview() {
        val prev = currentImagePath
        currentImagePath = null
        if (prev != null && prev !in savedPaths) {
            File(prev).delete()
        }
    }

    /** 启动清理：删除 7 天以上、无数据库引用的孤立缓存图片，并控制缓存容量上限 */
    private fun cleanupCacheOnStartup() {
        cleanupScope.launch {
            val validPaths = dao.getAllRecords().map { it.imagePath }.toSet()
            ImageCacheManager.cleanupExpired(applicationContext, validPaths)
            ImageCacheManager.enforceCapacity(applicationContext, validPaths)
        }
    }

    override fun onDestroy() {
        // 回收可能残留的预览 Bitmap，避免低内存设备 OOM（先判空与 isRecycled，防止重复回收）
        // 注意：分析进行中（isAnalyzing）不要回收 currentBitmap，
        // 否则可能与后台分析协程竞争同一 Bitmap 并触发崩溃（如感知哈希处理已回收对象）。
        if (!isAnalyzing) {
            currentBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            currentBitmap = null
        }
        // 退出时 best-effort 清理孤立缓存（进程被杀可能不触发，启动清理兜底）
        // 注意：不在此处 cleanScope.cancel()——cleanupScope 使用 SupervisorJob 且不持有 Activity 引用，
        // 取消反而会终止刚启动的清理协程。清理完成后子协程自然结束，无需显式取消。
        cleanupScope.launch {
            val validPaths = dao.getAllRecords().map { it.imagePath }.toSet() +
                    (currentImagePath?.let { setOf(it) } ?: emptySet())
            ImageCacheManager.cleanupExpired(applicationContext, validPaths)
            ImageCacheManager.enforceCapacity(applicationContext, validPaths)
        }
        super.onDestroy()
    }

    // ========== 免责声明 ==========

    private fun showDisclaimerDialog() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("disclaimer_accepted", false)) return

        val checkBox = CheckBox(this).apply {
            text = "不再提示"
            setPadding(40, 24, 40, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("使用须知")
            .setMessage(
                "本应用仅基于公开科普资料整理药品与食物的相克信息，" +
                        "不构成任何专业医疗建议，也不能替代医生诊断。" +
                        "服药期间的饮食禁忌请务必遵从医师或药师指导。" +
                        "使用即表示您已阅读并同意以上声明。"
            )
            .setView(checkBox)
            .setCancelable(false)
            .setPositiveButton("我已知晓") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                }
            }
            .show()
    }

    /**
     * 复制文本到剪贴板（供主界面结果与历史详情共用）
     */
    private fun copyText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "暂无可复制的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("药食忌口分析", text))
        Toast.makeText(this, "已复制分析结果", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复制主界面分析结果到剪贴板
     */
    private fun copyResult() {
        copyText(binding.tvResult.text?.toString().orEmpty())
    }

    // ========== 历史列表适配器（内部类） ==========

    inner class HistoryAdapter :
        ListAdapter<ScanRecord, HistoryAdapter.VH>(DIFF_CALLBACK) {

        // 复用日期格式化器，避免 onBindViewHolder 每次 new 造成 GC 压力
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        inner class VH(view: View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
            val tvResult: TextView = view.findViewById(R.id.tv_result)
            val tvTime: TextView = view.findViewById(R.id.tv_time)
            val riskDot: View = view.findViewById(R.id.iv_risk_dot)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scan_history, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            // 缩略图：imagePath 是绝对文件路径（无 file:// 前缀），
            // 必须用 File 加载，否则 Glide 的 FileUriLoader 无法识别导致永远显示占位图
            // 绑到具体 View（而非 Context）以跟随视图生命周期；历史项较多时
            // 全部 onBind 同步触发，使用 View 绑定可让 Glide 在视图脱离窗口时安全取消加载
            if (item.imagePath.isNotEmpty()) {
                Glide.with(holder.itemView)
                    .load(File(item.imagePath))
                    .override(112, 112)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .placeholder(R.drawable.bg_card_high_risk)
                    .error(R.drawable.bg_card_high_risk)
                    .into(holder.ivThumb)
            } else {
                // 纯文字查询记录没有图片，清除旧图并仅保留中性占位背景，避免误显示红色错误卡
                Glide.with(holder.itemView).clear(holder.ivThumb)
                holder.ivThumb.setImageDrawable(null)
            }
            // 结果预览（截取前80字）
            holder.tvResult.text = if (item.resultText.length > 80) {
                item.resultText.take(80) + "..."
            } else {
                item.resultText
            }
            // 时间
            holder.tvTime.text = dateFormat.format(Date(item.scanTime))
            // 风险圆点着色（与主界面同一套定级规则）
            val dotRes = when (resolveRiskLevel(item.resultText)) {
                RiskLevel.HIGH -> R.drawable.bg_risk_dot_high
                RiskLevel.LOW -> R.drawable.bg_risk_dot_low
                RiskLevel.SAFE -> R.drawable.bg_risk_dot_safe
            }
            holder.riskDot.setBackgroundResource(dotRes)

            // 点击仅弹出详情，不污染主界面"分析结果"栏
            holder.itemView.setOnClickListener {
                val spannable = highlightRiskText(item.resultText)
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("扫描详情")
                    .setMessage(spannable)
                    .setPositiveButton("复制") { _, _ -> copyText(item.resultText) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
            // 长按删除单条记录
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("删除记录")
                    .setMessage("确定删除这条扫描记录吗？对应图片也会一并删除。")
                    .setPositiveButton("删除") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val path = item.imagePath
                                dao.delete(item)
                                deleteImageFile(path)
                            } catch (e: Exception) {
                                // 删除失败不阻塞 UI，也不让异常杀掉进程
                            }
                        }
                        // 同步移除"已转正"标记
                        savedPaths = savedPaths - item.imagePath
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

    }
}
