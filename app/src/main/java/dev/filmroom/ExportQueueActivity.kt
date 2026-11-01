package dev.filmroom

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.filmroom.export.*
import dev.filmroom.ui.WrappingActionLayout
import dev.filmroom.ui.ExportStageRail
import dev.filmroom.ui.ExportPreviewView
import dev.filmroom.ui.ExportContourCache
import kotlinx.coroutines.*
import java.io.File

/** A durable task list, independent of the editor Activity and its preview engine. */
class ExportQueueActivity : ComponentActivity() {
    private var renderedLanguage="zh"
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(dev.filmroom.i18n.L10n.wrap(base))}

    companion object {
        const val EXTRA_JOB_ID="export_job_id"
        // Presentation memory only; never part of durable export state or image data.
        private val seenIntro=android.util.LruCache<String,Boolean>(256)
    }
    private val exportNotifications=dev.filmroom.ui.ExportNotificationPermission(this)
    private val ink=Color.rgb(244,241,233)
    private val muted=Color.rgb(157,159,155)
    private val accent=Color.rgb(220,185,132)
    private val stockNames:Map<String,String> by lazy{runCatching{
        val profiles=org.json.JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        (0 until profiles.length()).associate{profiles.getJSONObject(it).let{p->p.getString("id") to p.getString("name")}}
    }.getOrDefault(emptyMap())}
    private var queue:ExportQueue?=null
    internal var thumbnailDecoder:suspend (File)->android.graphics.Bitmap? = { decodeThumbnail(it) }
    private lateinit var rows:LinearLayout
    private lateinit var empty:TextView
    private lateinit var summary:TextView
    private lateinit var scroll:ScrollView
    private val cards=linkedMapOf<String,JobCard>()
    private var readRetryDelay=2_000L
    private val acknowledging=mutableSetOf<String>()
    private val completionDrawObserver=android.view.ViewTreeObserver.OnDrawListener {
        // onDraw proves the completed status has reached a foreground frame. Layout,
        // prefetch margins, Activity creation and offscreen binding are not a view.
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
            val displayed=cards.values.mapNotNull{it.visibleCompletion()}.filter{it.id !in acknowledging}
            if(displayed.isNotEmpty()) {
                acknowledging.addAll(displayed.map{it.id})
                scroll.post {
                    lifecycleScope.launch {
                        try {withContext(Dispatchers.IO){queue?.store?.markCompletionsViewed(displayed)};readRetryDelay=2_000L}
                        catch(c:CancellationException){throw c}
                        catch(_:Exception){
                            // A completed-only screen may otherwise never redraw. Retry quietly
                            // with backoff; background windows do not acknowledge or poll disk.
                            val wait=readRetryDelay;readRetryDelay=(readRetryDelay*2).coerceAtMost(30_000L)
                            delay(wait)
                            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus())scroll.invalidate()
                        }
                        finally {acknowledging.removeAll(displayed.map{it.id}.toSet())}
                    }
                }
            }
        }
    }
    private var focusId:String?=null
    private var restoreScroll:Int?=null
    private var restoreAnchor:String?=null
    private var restoreOffset=0
    private var expandedIds=mutableSetOf<String>()
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=16f)=TextView(this).apply { text=value;textSize=size;setTextColor(ink);includeFontPadding=false;setPadding(0,dp(4),0,dp(4)) }
    private fun surface(color:Int,radius:Int=14)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun button(value:String,primary:Boolean=false,action:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;textSize=14f;setTextColor(if(primary)Color.rgb(24,25,23)else muted)
        minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(64);minimumWidth=dp(64)
        setPadding(dp(14),dp(8),dp(14),dp(8));includeFontPadding=false;stateListAnimator=null;setBackgroundTintList(null)
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35,255,255,255)),surface(if(primary)ink else Color.TRANSPARENT,8),null)
        setOnClickListener{action()}
    }
    override fun onResume(){super.onResume();if(renderedLanguage!=dev.filmroom.i18n.L10n.language(this))recreate();if(::scroll.isInitialized)scroll.post{cards.values.forEach{it.updateThumbnailVisibility()}}}
    override fun onPause(){cards.values.forEach{it.pauseVisuals()};super.onPause()}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)ExportContourCache.clear()}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        renderedLanguage=dev.filmroom.i18n.L10n.language(this)
        savedInstanceState?.getStringArrayList("previewIntros")?.forEach{seenIntro.put(it,true)}
        focusId=savedInstanceState?.getString("focus") ?: intent.getStringExtra(EXTRA_JOB_ID)
        restoreAnchor=savedInstanceState?.getString("scrollAnchor");restoreOffset=savedInstanceState?.getInt("scrollOffset") ?: 0
        restoreScroll=savedInstanceState?.takeIf{it.containsKey("scrollY")}?.getInt("scrollY")
        expandedIds.addAll(savedInstanceState?.getStringArrayList("expanded") ?: emptyList())
        focusId?.let{expandedIds.add(it)}
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(15,17,16));setPadding(dp(16),dp(8),dp(16),0)}
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            addView(ImageButton(this@ExportQueueActivity).apply{
                contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_16b38790d59b);setImageResource(R.drawable.ic_back);imageTintList=android.content.res.ColorStateList.valueOf(ink)
                background=surface(Color.TRANSPARENT);setPadding(dp(12),dp(12),dp(12),dp(12))
                setOnClickListener{if(isTaskRoot)startActivity(Intent(this@ExportQueueActivity,MainActivity::class.java));finish()}
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(8)})
            addView(LinearLayout(this@ExportQueueActivity).apply{orientation=LinearLayout.VERTICAL
                addView(label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_88b41d5ab211),22f).apply{setTypeface(null,Typeface.BOLD)})
                summary=label("",11f).apply{setTextColor(muted);visibility=View.GONE}
                addView(summary)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        root.setOnApplyWindowInsetsListener{v,insets->v.setPadding(dp(16)+insets.systemWindowInsetLeft,dp(8)+insets.systemWindowInsetTop,dp(16)+insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets}
        root.addView(header)
        scroll=ScrollView(this).apply {id=R.id.export_queue_scroll;isSaveEnabled=false;isFillViewport=true}
        rows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(24))}
        empty=label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f361f0328380))
        rows.addView(empty);scroll.addView(rows)
        scroll.setOnScrollChangeListener { _,_,_,_,_->cards.values.forEach{it.updateThumbnailVisibility()} }
        rows.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->cards.values.forEach{it.updateThumbnailVisibility()}}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        scroll.viewTreeObserver.addOnDrawListener(completionDrawObserver)
        if(savedInstanceState==null)exportNotifications.offer()
        lifecycleScope.launch {
            try {
                val loaded=withContext(Dispatchers.IO){ExportQueue.get(applicationContext)};queue=loaded
                repeatOnLifecycle(Lifecycle.State.STARTED){loaded.jobs.collect{render(it)}}
            } catch(c:CancellationException){throw c}
            catch(e:Exception){empty.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3cd263a38563);toast(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_queue_read))}
        }
    }
    override fun onDestroy(){
        if(::scroll.isInitialized && scroll.viewTreeObserver.isAlive)scroll.viewTreeObserver.removeOnDrawListener(completionDrawObserver)
        super.onDestroy()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus && ::scroll.isInitialized)scroll.invalidate()}
    override fun onStop(){cards.values.forEach{it.pauseVisuals()};super.onStop()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);focusId=intent.getStringExtra(EXTRA_JOB_ID);focusId?.let{expandedIds.add(it)};queue?.jobs?.value?.let(::render)}
    override fun onSaveInstanceState(out:Bundle){
        out.putStringArrayList("previewIntros",ArrayList(cards.keys.filter{seenIntro.get(it)==true}))
        out.putInt("scrollY",scroll.scrollY)
        cards.entries.firstOrNull{it.value.container.bottom>scroll.scrollY}?.let{(id,card)->
            out.putString("scrollAnchor",id);out.putInt("scrollOffset",scroll.scrollY-card.container.top)
        }
        out.putString("focus",focusId);out.putStringArrayList("expanded",ArrayList(expandedIds));super.onSaveInstanceState(out)
    }
    private var renderedJobs:List<ExportJob> = emptyList()
    private fun render(jobs:List<ExportJob>) {
        val readOnlyChange=jobs!=renderedJobs && jobs.map{it.copy(completionViewed=false)}==renderedJobs.map{it.copy(completionViewed=false)}
        renderedJobs=jobs
        if(readOnlyChange && focusId==null && restoreScroll==null){jobs.forEach{cards[it.id]?.bind(it)};return}

        val anchor=cards.entries.firstOrNull{it.value.container.bottom>scroll.scrollY}?.let{it.key to (scroll.scrollY-it.value.container.top)}
        val processing=jobs.count{it.state==ExportJobState.RUNNING}
        val waiting=jobs.count{it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY)}
        val paused=jobs.count{it.state==ExportJobState.PAUSED}
        summary.text=listOfNotNull(if(processing>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f44ac2035ae, processing)else null,if(waiting>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_24d163f0f56e, waiting)else null,if(paused>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e982159d21f2, paused)else null).joinToString(" · ")
        summary.visibility=if(summary.text.isEmpty())View.GONE else View.VISIBLE
        empty.visibility=if(jobs.isEmpty())View.VISIBLE else View.GONE
        empty.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b331b1884a29)
        val ids=jobs.map{it.id}.toSet()
        cards.keys.filter{it !in ids}.toList().forEach{id->cards.remove(id)?.let{it.pauseVisuals();rows.removeView(it.container)}}
        jobs.forEachIndexed{index,job->cards.getOrPut(job.id){JobCard(job.id,index+1).also{rows.addView(it.container)}}.bind(job)}
        // Display newest additions first without changing the store's FIFO processing order.
        // Stable sort retains the store's deterministic order for equal creation timestamps.
        val displayCards=jobs.sortedByDescending{it.createdAt}.associateTo(linkedMapOf()){it.id to cards.getValue(it.id)}
        displayCards.values.forEachIndexed{index,card->
            val position=index+1 // The empty-state label remains the first child.
            if(rows.indexOfChild(card.container)!=position){rows.removeView(card.container);rows.addView(card.container,position)}
        }
        // Anchors must follow visual order, not the order in which cards were first created.
        cards.clear();cards.putAll(displayCards)
        if(jobs.isNotEmpty())restoreScroll?.let{y->
            val anchor=restoreAnchor;val offset=restoreOffset;restoreScroll=null
            afterRowsLayout{
                val restored=anchor?.let{cards[it]}?.container?.let{it.top+offset.coerceIn(-dp(20),(it.height-1).coerceAtLeast(0))} ?: y
                scroll.scrollTo(0,restored);cards.values.forEach{it.updateThumbnailVisibility()}
            }
        }
        if(restoreScroll==null && focusId==null && anchor!=null && scroll.scrollY>0)afterRowsLayout{cards[anchor.first]?.let{scroll.scrollTo(0,it.container.top+anchor.second.coerceIn(-dp(20),(it.container.height-1).coerceAtLeast(0)))}}
        focusId?.let{id->cards[id]?.let{card->afterRowsLayout{scroll.smoothScrollTo(0,card.container.top)};focusId=null}}
    }
    private fun afterRowsLayout(action:()->Unit) {
        if(rows.isLaidOut && !rows.isLayoutRequested){action();return}
        rows.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
            override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){
                rows.removeOnLayoutChangeListener(this);action()
            }
        })
    }
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun action(id:String,retry:Boolean) {
        val q=queue ?: return
        val card=cards[id] ?: return
        if(card.working)return
        card.working=true;card.bind(q.jobs.value.firstOrNull{it.id==id} ?: return)
        lifecycleScope.launch {
            var started=false
            var intake:java.io.Closeable?=null
            try {
                if(retry){val lease=ExportQueue.beginIntake(this@ExportQueueActivity);intake=lease;withContext(Dispatchers.IO){q.retry(id,lease.generation)};started=true}
                else withContext(NonCancellable+Dispatchers.IO){q.cancel(id)}
            } catch(c:CancellationException){throw c}
            catch(e:Exception){toast(if(retry)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_resume)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_cancel_failed))}
            finally{
                if(retry && !started)withContext(NonCancellable+Dispatchers.IO){runCatching{q.store.change(id){
                    if(it.state==ExportJobState.RETRY)it.copy(state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2d968e781718))else it
                }}}
                intake?.close()
                card.working=false;q.jobs.value.firstOrNull{it.id==id}?.let{card.bind(it)}
            }
        }
    }
    private inner class JobCard(private val id:String,private val number:Int) {
        var working=false
        private var latest:ExportJob?=null
        val container=LinearLayout(this@ExportQueueActivity).apply {
            orientation=LinearLayout.VERTICAL;tag="export-job:${this@JobCard.id}";setPadding(0,0,0,dp(16))
            background=surface(Color.rgb(22,25,23),12)
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)}
        }
        private val title=label("",12f).apply{setTextColor(muted);letterSpacing=.04f}
        private val image=ExportPreviewView(this@ExportQueueActivity).apply{tag="job-thumbnail:${this@JobCard.id}";bindJob(this@JobCard.id)}
        private val previewPlaceholder=ImageView(this@ExportQueueActivity).apply{setImageResource(R.drawable.ic_gallery);imageTintList=android.content.res.ColorStateList.valueOf(muted);setPadding(dp(66),dp(66),dp(66),dp(66));contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_916bd97fb66f)}
        private val progress=label("",20f).apply{minLines=2;setTypeface(null,Typeface.NORMAL)}
        private val bar=ExportStageRail(this@ExportQueueActivity).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        private val message=label("",13f).apply{setTextColor(muted)}
        private val details=label("",13f).apply{setTextColor(muted)}
        private val retry=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c9691192f1b),true){action(id,true)}
        private val cancel=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738)){android.app.AlertDialog.Builder(this@ExportQueueActivity).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4c777c000d4a)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a008667aeb14)).setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_670ec25af841),null).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_537d17f1c531)){_,_->action(id,false)}.show()}
        private val open=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9c1ea2e60a71),true){}
        private val detail=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)){if(!expandedIds.add(id))expandedIds.remove(id);latest?.let{bind(it)}}
        private var previewKey:String?=null
        private var previewVersion:String?=null
        private var previewCheckedAt=0L
        private var previewTask:Job?=null
        private var previewGeneration=0L
        private var introPending=false
        private lateinit var previewFrame:FrameLayout
        private lateinit var info:LinearLayout
        private lateinit var body:LinearLayout
        private val wide=resources.configuration.screenWidthDp>=600
        private var compact:Boolean?=null
        init {
            // A fixed photographic frame: loading and real source/negative/final swaps never move text.
            previewFrame=FrameLayout(this@ExportQueueActivity).apply {
                background=surface(Color.rgb(10,12,11),10);clipToOutline=true
                addView(image,FrameLayout.LayoutParams(-1,-1))
                addView(previewPlaceholder,FrameLayout.LayoutParams(-1,-1))
            }
            info=LinearLayout(this@ExportQueueActivity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),0)
                addView(title);addView(progress)
                addView(bar,LinearLayout.LayoutParams(-1,dp(4)).apply{topMargin=dp(10);bottomMargin=dp(8)})
                addView(message)
                addView(WrappingActionLayout(this@ExportQueueActivity,dp(4)).apply{addView(retry);addView(open);addView(detail);addView(cancel)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
                addView(details)
            }
            body=LinearLayout(this@ExportQueueActivity).apply {
                orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity=android.view.Gravity.TOP
                addView(previewFrame,LinearLayout.LayoutParams(if(wide)dp(224)else -1,dp(if(wide)180 else 176)))
                addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2))
            }
            container.addView(body)
        }
        private fun detailLabel(){detail.text=if(id in expandedIds)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0ae7312510f4)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)}
        fun bind(job:ExportJob) {
            val previous=latest
            latest=job
            if(job.state==ExportJobState.RUNNING && job.progress.stage==1 && seenIntro.get(id)!=true){introPending=true;seenIntro.put(id,true)}
            if(job.progress.stage>1)introPending=false
            if(previous!=null && previous!=job && previous.copy(completionViewed=job.completionViewed)==job)return
            val presentation=ExportJobPresentation.from(job)
            val concise=job.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY,ExportJobState.COMPLETED,ExportJobState.CANCELLED) && id !in expandedIds
            if(compact!=concise) {
                compact=concise
                val inset=dp(if(concise)24 else 66);previewPlaceholder.setPadding(inset,inset,inset,inset)
                val horizontal=wide || (concise && resources.configuration.fontScale<1.5f)
                body.orientation=if(horizontal)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                previewFrame.layoutParams=LinearLayout.LayoutParams(if(horizontal)dp(if(concise)104 else 224)else -1,dp(if(concise)112 else if(wide)180 else 176))
                info.layoutParams=if(horizontal)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2)
            }
            val stock=stockNames[job.parameters.filmStock] ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a97cb6107f92)
            val stateLabel=if(job.state in setOf(ExportJobState.COMPLETED,ExportJobState.CANCELLED,ExportJobState.FAILED,ExportJobState.PENDING,ExportJobState.RETRY))""else "${presentation.status} · "
            title.text="${number.toString().padStart(2,'0')}   $stateLabel$stock"
            progress.minLines=if(job.state in setOf(ExportJobState.RUNNING,ExportJobState.PAUSED,ExportJobState.FAILED))2 else 1
            title.setTextColor(if(job.state==ExportJobState.RUNNING)accent else muted)
            progress.text=android.text.SpannableString(presentation.progress).apply{
                val line=presentation.progress.indexOf('\n')
                if(line>=0){setSpan(android.text.style.RelativeSizeSpan(.65f),line+1,length,0);setSpan(android.text.style.ForegroundColorSpan(muted),line+1,length,0)}
            }
            bar.visibility=if(job.state in setOf(ExportJobState.RUNNING,ExportJobState.PAUSED,ExportJobState.FAILED))View.VISIBLE else View.GONE
            bar.bind(job.progress.displayStage,job.progress.displayPercent,job.state==ExportJobState.COMPLETED && job.progress.stage==8 && job.progress.percent==100,job.state==ExportJobState.RUNNING)
            progress.setTextColor(if(job.state==ExportJobState.FAILED)Color.rgb(239,181,157)else ink)
            message.setTextColor(if(job.state==ExportJobState.FAILED)ink else muted)
            message.text=job.localizedMessage ?: ""
            val importantMessage=job.metadataWarning!=null || job.state==ExportJobState.FAILED || !job.localizedMessage.isNullOrBlank()
            message.visibility=if(importantMessage && !job.localizedMessage.isNullOrBlank())View.VISIBLE else View.GONE
            retry.visibility=if(presentation.canRetry)View.VISIBLE else View.GONE;retry.text=if(job.state==ExportJobState.PAUSED)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c9691192f1b)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b8784c8dd563)
            cancel.visibility=if(presentation.canCancel)View.VISIBLE else View.GONE
            retry.isEnabled=!working;cancel.isEnabled=!working;retry.alpha=if(working).45f else 1f;cancel.alpha=retry.alpha
            retry.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_183e988f1288, number, retry.text);cancel.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_38952e7e372e, number);open.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3f3f6c8c5093, number);detail.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a9e8a732c910, number)
            open.visibility=if(job.state==ExportJobState.COMPLETED && job.mediaUri!=null)View.VISIBLE else View.GONE
            open.setOnClickListener {try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(job.mediaUri)).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}catch(e:Exception){toast(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_open))}}
            details.text=(if(job.state in setOf(ExportJobState.COMPLETED,ExportJobState.CANCELLED))job.progress.compactText+"\n"else "")+"${job.outputWidth} × ${job.outputHeight} · ${job.format.name}\n${stockNames[job.parameters.filmStock] ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a97cb6107f92)}\n${job.parameters.outputColorSpace}"+
                if(job.state==ExportJobState.PAUSED && job.inputReady)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3be614977d5d)else ""
            job.originalDiagnostics?.let { raw ->
                details.append("\n\n"+dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.queue_original_details)+"\n"+raw)
            }
            details.visibility=if(id in expandedIds)View.VISIBLE else View.GONE;detailLabel()
            updateThumbnailVisibility()
        }
        fun visibleCompletion():ExportJob? {
            val job=latest ?: return null
            if(job.state!=ExportJobState.COMPLETED || job.completionViewed || !progress.isShown)return null
            val visible=android.graphics.Rect()
            val viewport=android.graphics.Rect()
            // Require the status text, not just an edge of a thumbnail, to be visible.
            if(!progress.getGlobalVisibleRect(visible) || !scroll.getGlobalVisibleRect(viewport))return null
            return job.takeIf{visible.intersect(viewport) && visible.width()>0 && visible.height()>=minOf(progress.height,dp(16))}
        }
        fun pauseVisuals(){bar.pause();image.setForeground(false);previewGeneration++;previewTask?.cancel();previewKey=null}
        fun updateThumbnailVisibility() {
            val job=latest ?: return
            if(container.height==0)return
            // Keep only viewport-adjacent thumbnails alive; long histories cannot retain one bitmap per job.
            val near=container.bottom>=scroll.scrollY-dp(160) && container.top<=scroll.scrollY+scroll.height+dp(160)
            val visible=container.bottom>scroll.scrollY && container.top<scroll.scrollY+scroll.height && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            bar.setViewportVisible(visible)
            image.setForeground(visible)
            image.setBreathing(job.state==ExportJobState.RUNNING && job.progress.stage in 2..4 && previewVersion?.contains("negative-preview")!=true)
            if(!near){previewGeneration++;previewTask?.cancel();image.release();previewPlaceholder.visibility=View.VISIBLE;previewVersion=null;previewKey=null;return}
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
            val dir=queue?.store?.directory(id) ?: return
            val stageFourComplete=job.progress.stage==4 && job.progress.total>0 && job.progress.done==job.progress.total
            val key="${job.state}:${job.progress.stage}:$stageFourComplete"
            val now=android.os.SystemClock.elapsedRealtime()
            if(previewKey!=key || now-previewCheckedAt>=1000){
                previewKey=key;previewCheckedAt=now;previewTask?.cancel()
                val generation=++previewGeneration
                previewTask=lifecycleScope.launch {
                    val knownVersion=previewVersion
                    val (selection,version,bitmap)=withContext(Dispatchers.IO){
                        val selected=ExportPreviewSelection.select(dir,job.state,job.progress.stage,stageFourComplete)
                        val contour=selected.file?.name=="original-preview.jpg" && job.progress.stage>=1 && job.state !in setOf(ExportJobState.PENDING,ExportJobState.RETRY,ExportJobState.COMPLETED)
                        val version=selected.file?.let{"${it.name}:${it.lastModified()}:${it.length()}:${if(contour)"contour" else "photo"}"} ?: "absent"
                        val bitmap=if(version!=knownVersion)selected.file?.let { file ->
                            try {thumbnailDecoder(file)?.let { source ->
                                if(contour)ExportContourCache.get("$id:$version",source).also{source.recycle()} else source
                            }}catch(_:OutOfMemoryError){null}catch(_:Exception){null}
                        }else null
                        Triple(selected,version,bitmap)
                    }
                    if(previewGeneration==generation && previewKey==key && version!=previewVersion && bitmap!=null){
                        val intro=introPending && job.state==ExportJobState.RUNNING && job.progress.stage==1
                        introPending=false;previewVersion=version
                        image.show(bitmap,version,intro)
                        image.setBreathing(job.state==ExportJobState.RUNNING && job.progress.stage in 2..4 && selection.file?.name!="negative-preview.png")
                        previewPlaceholder.visibility=View.GONE
                        image.contentDescription=if(version.endsWith(":contour"))dev.filmroom.i18n.L10n.text(R.string.export_progress_preview)else selection.label
                        // Fixed slot; a failed or cancelled load never clears an already visible frame.
                    }
                }
            }
        }
    }
    private fun decodeThumbnail(file:File):android.graphics.Bitmap? {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>480)sample*=2
        return BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
    }
}

internal data class ExportJobPresentation(val status:String,val progress:String,val canRetry:Boolean,val canCancel:Boolean) {
    companion object {
        fun from(job:ExportJob)=ExportJobPresentation(when(job.state){
            ExportJobState.PENDING,ExportJobState.RETRY->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8fb3a3261b43)
            ExportJobState.RUNNING->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9aaf67962338)
            ExportJobState.PAUSED->if(job.inputReady)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_eb0c326b60ae)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_544c342feaf0)
            ExportJobState.COMPLETED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1bd91a7d0c53)
            ExportJobState.FAILED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6e4445df6957)
            ExportJobState.CANCELLED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a37778f17c5f)
        },when(job.state){
            ExportJobState.PENDING,ExportJobState.RETRY->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8fb3a3261b43)
            ExportJobState.COMPLETED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1bd91a7d0c53)
            ExportJobState.CANCELLED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a37778f17c5f)
            ExportJobState.FAILED->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a15bc3b4bd3e, job.progress.label, job.progress.displayStage)+(job.progress.displayPercent?.let{" · $it%"} ?: "")
            else->job.progress.compactText
        },job.canRetry && job.state in setOf(ExportJobState.PAUSED,ExportJobState.FAILED),!job.terminal)
    }
}
internal data class ExportPreviewSelection(val file:File?,val label:String) {
    companion object {
        fun select(dir:File,state:ExportJobState,stage:Int=8,stageFourComplete:Boolean=false):ExportPreviewSelection {
            val final=File(dir,"final-preview.jpg");val negative=File(dir,"negative-preview.png");val original=File(dir,"original-preview.jpg")
            return when {
                state==ExportJobState.COMPLETED && final.isFile && final.length()>0->ExportPreviewSelection(final,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a418b9270f7))
                (stage>=5 || (stage==4 && stageFourComplete)) && state!=ExportJobState.CANCELLED && negative.isFile && negative.length()>0->ExportPreviewSelection(negative,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_37eb7c18d5ef))
                original.isFile && original.length()>0->ExportPreviewSelection(original,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0b00a78a5faa))
                else->ExportPreviewSelection(null,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e3c1f7706839))
            }
        }
    }
}
