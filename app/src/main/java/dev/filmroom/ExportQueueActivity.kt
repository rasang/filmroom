package dev.filmroom

import dev.filmroom.ui.FilmroomPalette

import dev.filmroom.ui.ExportHistoryRemovalController
import dev.filmroom.ui.HistoryText
import dev.filmroom.model.ExportHistoryAction
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
        private val previewSequences=android.util.LruCache<String,dev.filmroom.ui.ExportPreviewSequence>(256)
    }
    private val exportNotifications=dev.filmroom.ui.ExportNotificationPermission(this)
    private val ink=FilmroomPalette.ink
    private val muted=FilmroomPalette.muted
    private val accent=FilmroomPalette.accent
    private val stockNames:Map<String,String> by lazy{runCatching{
        val profiles=org.json.JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        (0 until profiles.length()).associate{profiles.getJSONObject(it).let{p->p.getString("id") to p.getString("name")}}
    }.getOrDefault(emptyMap())}
    private var queue:ExportQueue?=null
    private val historyFlow=ExportHistoryRemovalController(this){queue?.jobs?.value?.let(::render)}
    internal var previewSequenceEnabled=true
    internal var previewClock:()->Long = { android.os.SystemClock.elapsedRealtime() }
    internal var previewWallClock:()->Long = { System.currentTimeMillis() }
    private var foregroundSince=Long.MAX_VALUE
    internal var thumbnailDecoder:suspend (File)->android.graphics.Bitmap? = { decodeThumbnail(it) }
    internal var savedThumbnailDecoder:suspend (String)->android.graphics.Bitmap? = { uri ->
        dev.filmroom.border.BorderMemoryAdmission.withBudget(ExportFinalPreview.WORKING_BYTES,
            {dev.filmroom.engine.NativeWorkReservations.snapshot(this)}) {ExportFinalPreview.decode(this,uri)}
    }
    private lateinit var rows:LinearLayout
    private lateinit var empty:TextView
    private lateinit var clearHistory:Button
    private lateinit var summary:TextView
    private lateinit var scroll:ScrollView
    // Presentation recovery is separate from export completion/read-notification state.
    private fun presentationAttempt(id:String):String = queue?.store?.directory(id)?.let{ScanPreviewStore.token(it)} ?: "legacy"
    private fun pendingPresentation(id:String)=ExportPresentationStore.pending(this,id,presentationAttempt(id))
    private fun rememberPresentation(id:String,active:Boolean)=ExportPresentationStore.remember(this,id,presentationAttempt(id),active)
    private val cards=linkedMapOf<String,JobCard>()
    private data class LeavingCard(val createdAt:Long,val view:View,val animator:android.animation.Animator)
    private val leavingCards=linkedMapOf<String,LeavingCard>()
    private fun finishCompletionAnimations(){if(::rows.isInitialized)android.transition.TransitionManager.endTransitions(rows)}
    private fun animateCompletionBounds(card:View) {
        if(!android.animation.ValueAnimator.areAnimatorsEnabled() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !card.isAttachedToWindow || !card.isLaidOut || card.width<=0 || card.height<=0 || leavingCards.isNotEmpty())return
        val visible=android.graphics.Rect()
        if(!card.getGlobalVisibleRect(visible) || visible.height()<=0)return
        // One scene-root transition batches simultaneous completions, including following rows.
        // The completed status/actions bind now; only bounds interpolate on the next frame.
        android.transition.TransitionManager.beginDelayedTransition(rows,android.transition.ChangeBounds().apply {
            duration=320L
            interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
        })
    }
    private fun finishRemovalAnimations(){leavingCards.values.toList().forEach{it.animator.end()}}
    private fun animateRemovedCard(id:String,card:JobCard,createdAt:Long):Boolean {
        if(id!=historyFlow.singleRemovalId || !android.animation.ValueAnimator.areAnimatorsEnabled() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !card.container.isAttachedToWindow || card.container.width<=0)return false
        finishCompletionAnimations()
        historyFlow.consumedRemovalAnimation(id)
        val view=card.container;card.pauseVisuals();view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        fun disable(v:View){v.isEnabled=false;if(v is android.view.ViewGroup)(0 until v.childCount).forEach{disable(v.getChildAt(it))}};disable(view)
        val scrollAnchor=if(view.bottom<=scroll.scrollY)cards.entries.firstOrNull{it.key!=id && it.value.container.bottom>scroll.scrollY}?.let{it.key to (scroll.scrollY-it.value.container.top)}else null
        val height=view.height;val originalMargin=(view.layoutParams as LinearLayout.LayoutParams).bottomMargin
        val slide=android.animation.ObjectAnimator.ofFloat(view,View.TRANSLATION_X,view.translationX,-(scroll.width+dp(32)).toFloat()).apply{duration=180;interpolator=android.view.animation.AccelerateInterpolator()}
        val collapse=android.animation.ValueAnimator.ofFloat(1f,0f).apply{duration=160;interpolator=android.view.animation.DecelerateInterpolator();addUpdateListener{a->val fraction=a.animatedValue as Float;view.layoutParams=(view.layoutParams as LinearLayout.LayoutParams).apply{this.height=(height*fraction).toInt();bottomMargin=(originalMargin*fraction).toInt()};scrollAnchor?.let{anchor->afterRowsLayout{cards[anchor.first]?.let{scroll.scrollTo(0,it.container.top+anchor.second)}}}}}
        val sequence=android.animation.AnimatorSet().apply{playSequentially(slide,collapse)}
        var finished=false
        sequence.addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){if(finished)return;finished=true;leavingCards.remove(id);rows.removeView(view);if(!isDestroyed)render(renderedJobs)}})
        leavingCards[id]=LeavingCard(createdAt,view,sequence);sequence.start();return true
    }
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
        text=value;isAllCaps=false;textSize=14f;setTextColor(if(primary)FilmroomPalette.onAccent else muted)
        minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(64);minimumWidth=dp(64)
        setPadding(dp(14),dp(8),dp(14),dp(8));includeFontPadding=false;stateListAnimator=null;setBackgroundTintList(null)
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35,255,255,255)),surface(if(primary)ink else Color.TRANSPARENT,8),null)
        setOnClickListener{action()}
    }
    override fun onResume(){foregroundSince=previewWallClock();super.onResume();if(renderedLanguage!=dev.filmroom.i18n.L10n.language(this))recreate();if(::scroll.isInitialized)scroll.post{cards.values.forEach{it.updateThumbnailVisibility()}}}
    override fun onPause(){finishCompletionAnimations();cards.values.forEach{it.pauseVisuals()};super.onPause()}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)ExportContourCache.clear()}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        historyFlow.restore(savedInstanceState)
        renderedLanguage=dev.filmroom.i18n.L10n.language(this)
        savedInstanceState?.getStringArrayList("previewIntros")?.forEach{seenIntro.put(it,true)}
        focusId=savedInstanceState?.getString("focus") ?: intent.getStringExtra(EXTRA_JOB_ID)
        restoreAnchor=savedInstanceState?.getString("scrollAnchor");restoreOffset=savedInstanceState?.getInt("scrollOffset") ?: 0
        restoreScroll=savedInstanceState?.takeIf{it.containsKey("scrollY")}?.getInt("scrollY")
        expandedIds.addAll(savedInstanceState?.getStringArrayList("expanded") ?: emptyList())
        focusId?.let{expandedIds.add(it)}
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(FilmroomPalette.background);setPadding(dp(16),dp(8),dp(16),0)}
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
        clearHistory=button(HistoryText.CLEAR.text(this)) { historyDialog(null) }.apply {
            tag="history-clear";contentDescription=HistoryText.CLEAR_SETTING.text(this@ExportQueueActivity);setTextColor(accent)
            setCompoundDrawablesRelative(HistoryActionIcon(accent,true).apply{setBounds(0,0,dp(19),dp(19))},null,null,null)
            compoundDrawablePadding=dp(6)
        }
        header.addView(clearHistory,LinearLayout.LayoutParams(-2,dp(48)))
        root.setOnApplyWindowInsetsListener{v,insets->v.setPadding(dp(16)+insets.systemWindowInsetLeft,dp(8)+insets.systemWindowInsetTop,dp(16)+insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets}
        root.addView(header)
        scroll=object:ScrollView(this) {
            override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
                // Finish geometry before a new gesture so scrolling and hit targets stay under the finger.
                if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)finishCompletionAnimations()
                return super.dispatchTouchEvent(event)
            }
        }.apply {id=R.id.export_queue_scroll;isSaveEnabled=false;isFillViewport=true}
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
                val loaded=withContext(Dispatchers.IO){ExportQueue.get(applicationContext)};queue=loaded;historyFlow.attach(loaded)
                repeatOnLifecycle(Lifecycle.State.STARTED){loaded.jobs.collect{render(it)}}
            } catch(c:CancellationException){throw c}
            catch(e:Exception){empty.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3cd263a38563);toast(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_queue_read))}
        }
    }
    override fun onDestroy(){
        finishCompletionAnimations();finishRemovalAnimations()
        historyFlow.close(isChangingConfigurations)
        if(::scroll.isInitialized && scroll.viewTreeObserver.isAlive)scroll.viewTreeObserver.removeOnDrawListener(completionDrawObserver)
        super.onDestroy()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus && ::scroll.isInitialized)scroll.invalidate()}
    override fun onStop(){finishRemovalAnimations();cards.values.forEach{it.pauseVisuals()};super.onStop()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);focusId=intent.getStringExtra(EXTRA_JOB_ID);focusId?.let{expandedIds.add(it)};queue?.jobs?.value?.let(::render)}
    override fun onSaveInstanceState(out:Bundle){
        historyFlow.save(out)
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
        val previousJobs=renderedJobs
        renderedJobs=jobs
        clearHistory.isEnabled=!historyFlow.busy && jobs.any{historyRemovable(it)}
        clearHistory.alpha=if(clearHistory.isEnabled)1f else .35f
        if(readOnlyChange && focusId==null && restoreScroll==null){jobs.forEach{cards[it.id]?.bind(it)};return}

        val anchor=cards.entries.firstOrNull{it.value.container.bottom>scroll.scrollY}?.let{it.key to (scroll.scrollY-it.value.container.top)}
        val processing=jobs.count{it.state==ExportJobState.RUNNING}
        val waiting=jobs.count{it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY)}
        val paused=jobs.count{it.state==ExportJobState.PAUSED}
        summary.text=listOfNotNull(if(processing>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f44ac2035ae, processing)else null,if(waiting>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_24d163f0f56e, waiting)else null,if(paused>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e982159d21f2, paused)else null).joinToString(" · ")
        summary.visibility=if(summary.text.isEmpty())View.GONE else View.VISIBLE
        empty.visibility=if(jobs.isEmpty() && leavingCards.isEmpty())View.VISIBLE else View.GONE
        empty.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b331b1884a29)
        val ids=jobs.map{it.id}.toSet()
        cards.keys.filter{it !in ids}.toList().forEach{id->cards.remove(id)?.let{card->ExportPresentationStore.clear(this,id);ExportContourCache.removeJob(id);if(!animateRemovedCard(id,card,previousJobs.firstOrNull{it.id==id}?.createdAt ?: 0L)){card.pauseVisuals();rows.removeView(card.container)}}}
        empty.visibility=if(jobs.isEmpty() && leavingCards.isEmpty())View.VISIBLE else View.GONE
        jobs.forEachIndexed{index,job->cards.getOrPut(job.id){JobCard(job.id,index+1).also{rows.addView(it.container)}}.bind(job)}
        // Display newest additions first without changing the store's FIFO processing order.
        // Stable sort retains the store's deterministic order for equal creation timestamps.
        val displayCards=jobs.sortedByDescending{it.createdAt}.associateTo(linkedMapOf()){it.id to cards.getValue(it.id)}
        // Include departing stable-ID rows until collapse ends, so incoming jobs/progress cannot move the wrong view.
        val orderedViews=(jobs.map{Triple(it.id,it.createdAt,cards.getValue(it.id).container as View)}+leavingCards.map{(id,leaving)->Triple(id,leaving.createdAt,leaving.view)}).sortedByDescending{it.second}
        orderedViews.forEachIndexed{index,entry->val position=index+1;val view=entry.third
            if(rows.indexOfChild(view)!=position){rows.removeView(view);rows.addView(view,position)}
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
    private fun historyRemovable(job:ExportJob)=job.state in setOf(ExportJobState.COMPLETED,ExportJobState.FAILED,ExportJobState.CANCELLED)
    private fun historyDialog(job:ExportJob?) {
        val q=queue ?: return
        val ids=if(job==null)renderedJobs.filter{historyRemovable(it)}.map{it.id}.toSet()else setOf(job.id)
        historyFlow.request(if(job==null)ExportHistoryAction.CLEAR else ExportHistoryAction.SINGLE,q.historySnapshot(ids))
    }
    private fun afterRowsLayout(action:()->Unit) {
        if(rows.isLaidOut && !rows.isLayoutRequested && scroll.isLaidOut && !scroll.isLayoutRequested){action();return}
        // The ScrollView clamps/restores focus after laying out rows. Restore our stable
        // job anchor only after that outer viewport pass, including rotation and compaction.
        val target:View=if(scroll.isLayoutRequested || !scroll.isLaidOut)scroll else rows
        target.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
            override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){
                target.removeOnLayoutChangeListener(this);action()
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
            background=surface(FilmroomPalette.surface,12)
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
        private val detail=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)){if(!expandedIds.add(id))expandedIds.remove(id);latest?.let{bind(it)}}.apply{
            setCompoundDrawablesRelative(HistoryDetailIcon(muted).apply{setBounds(0,0,dp(18),dp(18))},null,null,null);compoundDrawablePadding=dp(6)
        }
        private val delete=button(HistoryText.DELETE.text(this@ExportQueueActivity)) { latest?.takeIf{historyRemovable(it)}?.let{historyDialog(it)} }.apply {
            tag="history-delete:${this@JobCard.id}"
            setCompoundDrawablesRelative(HistoryActionIcon(muted,false).apply{setBounds(0,0,dp(18),dp(18))},null,null,null)
            compoundDrawablePadding=dp(6)
        }
        private lateinit var actions:WrappingActionLayout
        private var previewKey:String?=null
        private var previewVersion:String?=null
        private var previewCheckedAt=0L
        private var previewTask:Job?=null
        private var previewGeneration=0L
        private val sequence=previewSequences.get(id) ?: dev.filmroom.ui.ExportPreviewSequence().also{previewSequences.put(id,it)}
        private var sequenceTask:Job?=null
        private var introPending=false
        private fun presentingCompletion(job:ExportJob)=previewSequenceEnabled && sequence.active && job.state==ExportJobState.COMPLETED
        private fun presentVisuals(job:ExportJob) {
            // Finish every genuine image's visible dwell before showing Saved. This is
            // presentation only: durable completion, notifications and the next export
            // still follow the real save. The result action follows this card's status.
            val concise=updateCompactLayout(job)
            val finishing=presentingCompletion(job)
            val canOpen=job.state==ExportJobState.COMPLETED && job.mediaUri!=null && !finishing
            val openVisibility=if(canOpen)View.VISIBLE else View.GONE
            // Timers and lifecycle recovery call this without another queue emission.
            // Bind the action with the rail/status so it cannot appear early or stay hidden.
            if(open.visibility!=openVisibility)open.visibility=openVisibility
            if(open.isEnabled!=canOpen)open.isEnabled=canOpen
            val compactMetadataWarning=concise && job.state==ExportJobState.COMPLETED && !job.metadataWarning.isNullOrBlank()
            val messageVisibility=if(!finishing && !compactMetadataWarning && !job.localizedMessage.isNullOrBlank())View.VISIBLE else View.GONE
            if(message.visibility!=messageVisibility)message.visibility=messageVisibility
            rememberPresentation(id,sequence.active)
            val (stage,percent)=sequence.constrain(job.progress.displayStage,job.progress.displayPercent)
            val visibility=if(finishing || job.state in setOf(ExportJobState.RUNNING,ExportJobState.PAUSED,ExportJobState.FAILED))View.VISIBLE else View.GONE
            if(bar.visibility!=visibility)bar.visibility=visibility
            bar.bind(stage,percent,!sequence.active && job.state==ExportJobState.COMPLETED && job.progress.stage==8 && job.progress.percent==100,job.state==ExportJobState.RUNNING || finishing)
            // Failure, cancellation and pause are immediate. Success text follows the
            // same presentation clock as the rail and the final full-size photograph.
            val minLines=if(finishing || job.state in setOf(ExportJobState.RUNNING,ExportJobState.PAUSED,ExportJobState.FAILED))2 else 1
            if(progress.minLines!=minLines)progress.minLines=minLines
            val text=if(sequence.active && (job.state==ExportJobState.RUNNING || finishing))
                dev.filmroom.engine.ExportDisplayProgress(stage,visual=dev.filmroom.engine.ExportVisualProgress(stage,percent)).compactText
                else ExportJobPresentation.from(job).progress
            // Called by layout/viewport observers: rewriting identical text would request
            // another layout and feed the observer forever, even with no progress change.
            if(progress.text.toString()!=text)progress.text=android.text.SpannableString(text).apply {
                val line=indexOf('\n')
                if(line>=0){setSpan(android.text.style.RelativeSizeSpan(.65f),line+1,length,0);setSpan(android.text.style.ForegroundColorSpan(muted),line+1,length,0)}
            }
        }
        private fun scheduleSequence(visible:Boolean) {
            sequenceTask?.cancel();sequenceTask=null
            if(!visible || !sequence.active)return
            val remaining=sequence.remaining(previewClock())
            sequenceTask=lifecycleScope.launch {
                // Poll genuine late previews while visible, including after actual completion.
                delay(if(remaining>0)remaining else 300)
                sequenceTask=null;sequence.finish(previewClock());previewKey=null
                latest?.let{presentVisuals(it)}
                updateThumbnailVisibility()
            }
        }
        private lateinit var previewFrame:FrameLayout
        private lateinit var info:LinearLayout
        private lateinit var body:LinearLayout
        private val wide=resources.configuration.screenWidthDp>=600
        private var compact:Boolean?=null
        init {
            // A fixed photographic frame: loading and real source/negative/final swaps never move text.
            previewFrame=FrameLayout(this@ExportQueueActivity).apply {
                background=surface(FilmroomPalette.inset,10);clipToOutline=true
                addView(image,FrameLayout.LayoutParams(-1,-1))
                addView(previewPlaceholder,FrameLayout.LayoutParams(-1,-1))
            }
            info=LinearLayout(this@ExportQueueActivity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),0)
                addView(title);addView(progress)
                addView(bar,LinearLayout.LayoutParams(-1,dp(4)).apply{topMargin=dp(10);bottomMargin=dp(8)})
                addView(message)

                addView(details)
            }
            body=LinearLayout(this@ExportQueueActivity).apply {
                orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity=android.view.Gravity.TOP
                addView(previewFrame,LinearLayout.LayoutParams(if(wide)dp(224)else -1,dp(if(wide)180 else 176)))
                addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2))
            }
            container.addView(body)
            actions=WrappingActionLayout(this@ExportQueueActivity,dp(4)).apply{
                setPadding(dp(12),dp(6),dp(12),0)
                addView(retry);addView(open);addView(detail);addView(delete);addView(cancel)
            }
            container.addView(actions,LinearLayout.LayoutParams(-1,-2))
        }
        private fun updateCompactLayout(job:ExportJob):Boolean {
            val finishingPresentation=presentingCompletion(job)
            val concise=job.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY,ExportJobState.COMPLETED,ExportJobState.CANCELLED) && id !in expandedIds && !finishingPresentation
            if(compact!=concise) {
                val anchor=if(compact!=null && scroll.scrollY>0 && container.bottom<=scroll.scrollY)
                    cards.entries.firstOrNull{it.value.container.bottom>scroll.scrollY}?.let{it.key to (scroll.scrollY-it.value.container.top)} else null
                if(compact==false && concise && job.state==ExportJobState.COMPLETED)
                    animateCompletionBounds(container)
                compact=concise
                val inset=dp(if(concise)24 else 66);previewPlaceholder.setPadding(inset,inset,inset,inset)
                val horizontal=wide || (concise && resources.configuration.fontScale<1.5f)
                body.orientation=if(horizontal)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                // Compact photographs sit inside the card rather than sharing its outer edge.
                // Trade 12dp of image width for the inset, preserving the text/action column width.
                container.setPadding(0,0,0,dp(if(concise)12 else 16))
                previewFrame.layoutParams=LinearLayout.LayoutParams(if(horizontal)dp(if(concise)92 else 224)else -1,dp(if(concise)100 else if(wide)180 else 176)).apply {
                    if(concise){marginStart=dp(12);topMargin=dp(12);if(!horizontal)marginEnd=dp(12)}
                }
                info.layoutParams=if(horizontal)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2)
                anchor?.let{saved->afterRowsLayout{cards[saved.first]?.let{scroll.scrollTo(0,it.container.top+saved.second)}}}
            }
            return concise
        }
        private fun detailLabel(){detail.text=if(id in expandedIds)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0ae7312510f4)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)}
        fun bind(job:ExportJob) {
            val previous=latest
            latest=job
            if(previous==null && sequence.active && !pendingPresentation(id))sequence.reset()
            if(job.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY) ||
                (previous!=null && job.progress.stage<previous.progress.stage))sequence.reset()
            if(previewSequenceEnabled && !sequence.started && job.state in setOf(ExportJobState.RUNNING,ExportJobState.COMPLETED)) {
                val startedAt=queue?.store?.directory(id)?.let(ScanPreviewStore::startedAtMillis)
                // Unread alone does not start a replay. Recent background completion
                // recovers only the remainder proven by this attempt's real artifacts.
                if(job.state==ExportJobState.RUNNING || (!job.completionViewed && startedAt!=null)) {
                    sequence.start()
                    if(job.state==ExportJobState.RUNNING && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        (startedAt==null || startedAt>=foregroundSince))sequence.visibility(true,previewClock())
                }
            }
            refreshSequenceTimeline(job)
            sequence.finish(previewClock())
            if(!previewSequenceEnabled || job.state in setOf(ExportJobState.FAILED,ExportJobState.CANCELLED,ExportJobState.PAUSED)) {
                sequence.stop();sequenceTask?.cancel();sequenceTask=null
                if(job.state in setOf(ExportJobState.FAILED,ExportJobState.CANCELLED,ExportJobState.PAUSED)) {
                    previewGeneration++;previewTask?.cancel();previewKey=null
                }
            }
            if(job.state==ExportJobState.RUNNING && job.progress.displayStage==1 && seenIntro.get(id)!=true){introPending=true;seenIntro.put(id,true)}
            if(job.progress.displayStage>1)introPending=false
            if(previous!=null && previous!=job && previous.copy(completionViewed=job.completionViewed)==job)return
            val presentation=ExportJobPresentation.from(job)
            val stock=stockNames[job.parameters.filmStock] ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a97cb6107f92)
            val stateLabel=if(job.state in setOf(ExportJobState.COMPLETED,ExportJobState.CANCELLED,ExportJobState.FAILED,ExportJobState.PENDING,ExportJobState.RETRY))""else "${presentation.status} · "
            title.text="${number.toString().padStart(2,'0')}   $stateLabel$stock"
            title.setTextColor(if(job.state==ExportJobState.RUNNING)accent else muted)
            presentVisuals(job)
            progress.setTextColor(if(job.state==ExportJobState.FAILED)Color.rgb(239,181,157)else ink)
            message.setTextColor(if(job.state==ExportJobState.FAILED)ink else muted)
            message.text=job.localizedMessage ?: ""
            retry.visibility=if(presentation.canRetry)View.VISIBLE else View.GONE;retry.text=if(job.state==ExportJobState.PAUSED)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c9691192f1b)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b8784c8dd563)
            cancel.visibility=if(presentation.canCancel && !historyRemovable(job))View.VISIBLE else View.GONE
            delete.isEnabled=historyRemovable(job) && !working && !historyFlow.busy
            delete.alpha=if(delete.isEnabled)1f else .28f
            delete.contentDescription=HistoryText.DELETE_A11Y.text(this@ExportQueueActivity,number) + if(!delete.isEnabled) " · "+HistoryText.DISABLED.text(this@ExportQueueActivity) else ""
            retry.isEnabled=!working && queue?.store?.historyReserved(id)!=true;cancel.isEnabled=!working && queue?.store?.historyReserved(id)!=true;retry.alpha=if(working).45f else 1f;cancel.alpha=retry.alpha
            retry.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_183e988f1288, number, retry.text);cancel.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_38952e7e372e, number);open.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3f3f6c8c5093, number);detail.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a9e8a732c910, number)
            open.setOnClickListener {
                val ready=latest?.takeIf{it.state==ExportJobState.COMPLETED && it.mediaUri!=null && !presentingCompletion(it)} ?: return@setOnClickListener
                try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ready.mediaUri)).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}
                catch(e:Exception){toast(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_open))}
            }
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
            if(job.state!=ExportJobState.COMPLETED || presentingCompletion(job) || job.completionViewed || !progress.isShown)return null
            val visible=android.graphics.Rect()
            val viewport=android.graphics.Rect()
            // Require the status text, not just an edge of a thumbnail, to be visible.
            if(!progress.getGlobalVisibleRect(visible) || !scroll.getGlobalVisibleRect(viewport))return null
            return job.takeIf{visible.intersect(viewport) && visible.width()>0 && visible.height()>=minOf(progress.height,dp(16))}
        }
        private fun refreshSequenceTimeline(job:ExportJob) {
            if(!sequence.needsTimeline)return
            val dir=queue?.store?.directory(id) ?: return
            val start=ScanPreviewStore.startedAtMillis(dir)
            val now=previewClock();val wall=previewWallClock()
            fun clock(time:Long?)=time?.takeIf{it>0}?.let{now-(wall-it).coerceAtLeast(0)}
            val originalAt=start?.let { began ->
                File(dir,"original-preview.jpg").takeIf{it.isFile && it.length()>0}?.let{maxOf(began,it.lastModified())}
            }
            val negativeAt=NegativePreviewStore.readyAtMillis(dir)
            val scan=ScanPreviewStore.select(dir)
            val final=File(dir,"final-preview.jpg").takeIf{job.state==ExportJobState.COMPLETED && it.isFile && it.length()>0}
            val positiveAt=listOfNotNull(scan,final).map{it.lastModified()}.filter{start==null || it>=start}.minOrNull()
            sequence.timeline(clock(originalAt),clock(negativeAt),clock(positiveAt),
                !NegativePreviewStore.pending(dir) && (job.state==ExportJobState.COMPLETED || job.progress.displayStage>=6))
        }
        fun pauseVisuals(){sequence.visibility(false,previewClock());sequenceTask?.cancel();sequenceTask=null;bar.pause();image.setForeground(false);previewGeneration++;previewTask?.cancel();previewKey=null}
        fun updateThumbnailVisibility() {
            val job=latest ?: return
            if(container.height==0)return
            // Keep only viewport-adjacent thumbnails alive; long histories cannot retain one bitmap per job.
            val near=container.bottom>=scroll.scrollY-dp(160) && container.top<=scroll.scrollY+scroll.height+dp(160)
            val visible=container.bottom>scroll.scrollY && container.top<scroll.scrollY+scroll.height && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            bar.setViewportVisible(visible)
            image.setForeground(visible)
            refreshSequenceTimeline(job)
            sequence.visibility(visible,previewClock())
            sequence.finish(previewClock());presentVisuals(job);scheduleSequence(visible)
            val visibleStage=sequence.constrain(job.progress.displayStage,job.progress.displayPercent).first
            image.setBreathing((job.state==ExportJobState.RUNNING || sequence.active) && visibleStage in 2..4 && previewVersion?.contains("negative-preview")!=true,visibleStage==3)
            if(!near){previewGeneration++;previewTask?.cancel();image.release();previewPlaceholder.visibility=View.VISIBLE;previewVersion=null;previewKey=null;return}
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
            if(job.state in setOf(ExportJobState.CANCELLED,ExportJobState.FAILED) && previewVersion!=null)return
            val dir=queue?.store?.directory(id) ?: return
            val stageFourComplete=job.progress.displayStage==4 && job.progress.displayPercent==100
            val sequenceActive=sequence.active
            val sequencePhase=sequence.phase
            val negativeName=sequence.negativeName
            val sequenceHolding=sequence.remaining(previewClock())>0
            val previewAttempt=ScanPreviewStore.token(dir)
            val key="${job.state}:${job.progress.displayStage}:$stageFourComplete:$sequenceActive:$sequencePhase:$sequenceHolding:$previewAttempt"
            val now=android.os.SystemClock.elapsedRealtime()
            if(previewKey!=key || now-previewCheckedAt>=1000){
                previewKey=key;previewCheckedAt=now;previewTask?.cancel()
                val generation=++previewGeneration
                previewTask=lifecycleScope.launch {
                    val knownVersion=previewVersion
                    var ownedBitmap:android.graphics.Bitmap?=null
                    try {
                    val (loadedPreview,positiveUnavailable,negativeAvailable)=withContext(Dispatchers.IO){
                        val newest=ExportPreviewSelection.select(dir,job.state,job.progress.displayStage,stageFourComplete,negativeName)
                        fun positive(file:File?)=file?.name=="final-preview.jpg" || file?.name?.startsWith("scan-preview-")==true
                        var positiveUnavailable= !positive(newest.file)
                        val original=ExportPreviewSelection.select(dir,ExportJobState.RUNNING,1)
                        val negative=ExportPreviewSelection.select(dir,ExportJobState.RUNNING,5,negativeName=negativeName)
                        var negativeAvailable=NegativePreviewStore.isNegative(negative.file)
                        val pendingNegative= !NegativePreviewStore.isNegative(negative.file) && (NegativePreviewStore.pending(dir) || job.state==ExportJobState.RUNNING)
                        val selected=when {
                            sequenceActive && sequencePhase<=1 && pendingNegative && original.file?.name=="original-preview.jpg"->original
                            sequenceActive && sequencePhase<=1 && (sequencePhase==0 || sequenceHolding) && original.file?.name=="original-preview.jpg"->original
                            sequenceActive && sequencePhase<=2 && NegativePreviewStore.isNegative(negative.file) &&
                                ((sequencePhase==2 && sequenceHolding) || (sequencePhase<2 && (newest.file?.name=="final-preview.jpg" || newest.file?.name?.startsWith("scan-preview-")==true)))->negative
                            else->newest
                        }
                        suspend fun load(selected:ExportPreviewSelection):Triple<ExportPreviewSelection,String,android.graphics.Bitmap?> {
                        val contour=selected.file?.name=="original-preview.jpg" && job.progress.displayStage>=1 && (sequenceActive || job.state !in setOf(ExportJobState.PENDING,ExportJobState.RETRY,ExportJobState.COMPLETED))
                        val version=selected.file?.let{"${it.name}:${it.lastModified()}:${it.length()}:${if(contour)"v${ExportContourCache.VERSION}:contour" else "photo"}"} ?: "absent"
                        currentCoroutineContext().ensureActive()
                        val cachedContour=if(contour && version!=knownVersion)ExportContourCache.find("$id:$version")else null
                        val bitmap=if(version!=knownVersion)cachedContour ?: selected.file?.let { file ->
                            try {thumbnailDecoder(file)?.let { decoded ->
                                // Old installations retain 240px final thumbnails. Read only a
                                // bounded display derivative from the saved media, off-thread;
                                // permission/decode/budget failures keep the old valid pixels.
                                val source=if(file.name=="final-preview.jpg" && job.state==ExportJobState.COMPLETED &&
                                    job.mediaUri!=null && maxOf(decoded.width,decoded.height)<ExportFinalPreview.MAX_EDGE) {
                                    val upgraded=try {savedThumbnailDecoder(job.mediaUri)}catch(c:CancellationException){decoded.recycle();throw c}catch(_:Exception){null}catch(_:OutOfMemoryError){null}
                                    if(upgraded!=null && maxOf(upgraded.width,upgraded.height)>maxOf(decoded.width,decoded.height)) {
                                        decoded.recycle();upgraded
                                    }else {upgraded?.takeUnless{it===decoded}?.recycle();decoded}
                                }else decoded
                                if(contour)try {
                                    val cacheKey="$id:$version"
                                    val context=currentCoroutineContext()
                                    context.ensureActive()
                                    ExportContourCache.find(cacheKey) ?: dev.filmroom.border.BorderMemoryAdmission.withBudget(
                                        ExportContourCache.WORKING_BYTES,{dev.filmroom.engine.NativeWorkReservations.snapshot(this@ExportQueueActivity)}
                                    ) {ExportContourCache.get(cacheKey,source){context.ensureActive()}}
                                } finally {source.recycle()} else source.also{ownedBitmap=it}
                            }}catch(c:CancellationException){throw c}catch(_:OutOfMemoryError){null}catch(_:Exception){null}
                        }else null
                        if(positive(selected.file) && version!=knownVersion)positiveUnavailable=bitmap==null
                        if(NegativePreviewStore.isNegative(selected.file))negativeAvailable=bitmap!=null || version==knownVersion
                        return Triple(selected,version,bitmap)
                        }
                        val alternatives=if(sequenceActive && sequencePhase<=2)NegativePreviewStore.alternatives(dir,negativeName).map{ExportPreviewSelection(it,negative.label)}else emptyList()
                        val candidates=(listOf(selected)+alternatives+listOfNotNull(newest.takeUnless{sequenceActive && sequencePhase<=1 && (pendingNegative || NegativePreviewStore.pending(dir))})).distinctBy{it.file}
                        var loaded=load(candidates.first())
                        for(candidate in candidates.drop(1)) {
                            if(loaded.third!=null || loaded.second==knownVersion)break
                            loaded=load(candidate)
                        }
                        Triple(loaded,positiveUnavailable,negativeAvailable)
                    }
                    val (selection,version,bitmap)=loadedPreview
                    val scanStillCurrent=ScanPreviewStore.token(dir)==previewAttempt && (selection.file?.let { file ->
                        when {NegativePreviewStore.isNegative(file)->NegativePreviewStore.current(dir,file)
                            file.name.startsWith("scan-preview-")->ScanPreviewStore.select(dir)?.name==file.name
                            else->true}
                    } ?: true)
                    val selectedPhase=when {version.endsWith(":contour")->1;NegativePreviewStore.isNegative(selection.file)->2;else->3}
                    if(scanStillCurrent && previewGeneration==generation && previewKey==key && version!=previewVersion && bitmap!=null && (!sequence.active || selectedPhase>=sequence.phase)){
                        val intro=(introPending || (sequence.active && sequence.phase==0)) && version.endsWith(":contour")
                        introPending=false;previewVersion=version
                        val shownPhase=selectedPhase
                        if(shownPhase==2)selection.file?.name?.let{sequence.rememberNegative(it)}
                        sequence.shown(shownPhase,previewClock())
                        // The fade is inside the 500ms phase, including a short remainder
                        // recovered after backgrounding, rotation or a late image decode.
                        image.show(bitmap,version,intro,if(sequence.active)sequence.remaining(previewClock())else ExportPreviewView.FADE_DURATION_MS)
                        presentVisuals(latest ?: job);scheduleSequence(visible)
                        ownedBitmap=null // Ownership transferred only after acceptance; cached contours are never owned.
                        val shownStage=sequence.constrain(job.progress.displayStage,job.progress.displayPercent).first
                        image.setBreathing((job.state==ExportJobState.RUNNING || sequence.active) && shownStage in 2..4 && !NegativePreviewStore.isNegative(selection.file),shownStage==3)
                        previewPlaceholder.visibility=View.GONE
                        image.contentDescription=if(version.endsWith(":contour"))dev.filmroom.i18n.L10n.text(R.string.export_progress_preview)else selection.label
                        // Fixed slot; a failed or cancelled load never clears an already visible frame.
                    }
                    // A held original/negative selection can return after its dwell
                    // expires. That does not mean the next real artifact is absent.
                    val noPositive=job.state==ExportJobState.COMPLETED && sequence.phase>=1 && sequence.remaining(previewClock())==0L &&
                        positiveUnavailable && (sequence.phase>=2 || !negativeAvailable)
                    if(scanStillCurrent && previewGeneration==generation && previewKey==key &&
                        (noPositive || (bitmap==null && version!=previewVersion)) && job.state==ExportJobState.COMPLETED && !NegativePreviewStore.pending(dir)) {
                        sequence.stop();sequenceTask?.cancel();sequenceTask=null;presentVisuals(job)
                    }
                    } finally {ownedBitmap?.recycle()}
                }
            }
        }
    }
    private fun decodeThumbnail(file:File):android.graphics.Bitmap? {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
        val edge=when {NegativePreviewStore.isNegative(file)->NegativePreviewStore.MAX_EDGE
            file.name=="final-preview.jpg"->ExportFinalPreview.MAX_EDGE
            file.name.startsWith("scan-preview-")->ScanPreviewStore.MAX_EDGE
            else->480}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge)sample*=2
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
        fun select(dir:File,state:ExportJobState,stage:Int=8,stageFourComplete:Boolean=false,negativeName:String?=null):ExportPreviewSelection {
            val scan=if(state==ExportJobState.RUNNING && stage>=6)ScanPreviewStore.select(dir)else null
            val final=File(dir,"final-preview.jpg");val negative=NegativePreviewStore.select(dir,negativeName);val original=File(dir,"original-preview.jpg")
            return when {
                state==ExportJobState.COMPLETED && final.isFile && final.length()>0->ExportPreviewSelection(final,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a418b9270f7))
                scan!=null->ExportPreviewSelection(scan,dev.filmroom.i18n.L10n.text(R.string.export_progress_preview))
                (stage>=5 || (stage==4 && stageFourComplete)) && state!=ExportJobState.CANCELLED && negative!=null->ExportPreviewSelection(negative,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_37eb7c18d5ef))
                original.isFile && original.length()>0->ExportPreviewSelection(original,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0b00a78a5faa))
                else->ExportPreviewSelection(null,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e3c1f7706839))
            }
        }
    }
}

/** Original 24-unit vector paths, matched 1.7-unit round strokes. No borrowed glyphs. */
private class HistoryActionIcon(private val color:Int,private val clear:Boolean):android.graphics.drawable.Drawable(){
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{style=android.graphics.Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=android.graphics.Paint.Cap.ROUND;strokeJoin=android.graphics.Paint.Join.ROUND;color=this@HistoryActionIcon.color}
    override fun draw(c:android.graphics.Canvas){
        val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        val p=android.graphics.Path()
        if(clear){p.moveTo(4f,5f);p.lineTo(20f,5f);p.moveTo(4f,10f);p.lineTo(17f,10f);p.moveTo(4f,15f);p.lineTo(10f,15f);p.moveTo(4f,20f);p.lineTo(9f,20f);p.moveTo(15f,15f);p.lineTo(21f,21f);p.moveTo(21f,15f);p.lineTo(15f,21f)}
        else {p.moveTo(4f,6f);p.lineTo(20f,6f);p.moveTo(9f,6f);p.lineTo(9f,3f);p.lineTo(15f,3f);p.lineTo(15f,6f);p.moveTo(6f,6f);p.lineTo(7f,21f);p.lineTo(17f,21f);p.lineTo(18f,6f);p.moveTo(10f,10f);p.lineTo(10.5f,17f);p.moveTo(14f,10f);p.lineTo(13.5f,17f)}
        c.drawPath(p,paint);c.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}

private class HistoryDetailIcon(private val color:Int):android.graphics.drawable.Drawable(){
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{style=android.graphics.Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=android.graphics.Paint.Cap.ROUND;strokeJoin=android.graphics.Paint.Join.ROUND;color=this@HistoryDetailIcon.color}
    override fun draw(c:android.graphics.Canvas){val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        c.drawRoundRect(4f,3f,20f,21f,2f,2f,paint);val p=android.graphics.Path();p.moveTo(8f,8f);p.lineTo(16f,8f);p.moveTo(8f,12f);p.lineTo(16f,12f);p.moveTo(8f,16f);p.lineTo(13f,16f);c.drawPath(p,paint);c.restoreToCount(save)}
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()};override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}
