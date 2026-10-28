package com.hugo.projector

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Native games share a fixed-step host and low-resolution drawing contract. */
interface ArcadeGame {
    val active:Boolean
    val phase:String
    fun step()
    fun render(screen:Canvas,paint:Paint)
    fun recordFps(value:Int)
}

/** Authoritative, bounded native arcade simulation. No phone-side game state. */
object TankDuel : ArcadeGame {
    data class Tank(var x:Double, var y:Double, var angle:Double, var score:Int=0)
    data class Shot(var x:Double,var y:Double,val dx:Double,val dy:Double,val owner:Int,var life:Int=100)
    data class Slot(var token:String="",var session:String="",var seq:Long=0,var at:Long=0,var move:Double=0.0,var turn:Double=0.0,var fire:Boolean=false)
    val tanks=arrayOf(Tank(24.0,80.0,0.0),Tank(232.0,80.0,PI))
    val slots=arrayOf(Slot(),Slot())
    val shots=mutableListOf<Shot>()
    override var phase="IDLE"; private set
    var matchId=""; private set
    var config=JSONObject(); private set
    var tick=0L; private set
    var collisions=0; private set
    var hits=0; private set
    var fps=0; override var active=false
    private var roundDelay=0
    private val cooldown=IntArray(2)
    private var context:Context?=null
    private var tone:ToneGenerator?=null
    private var lastMatch=""
    val palette=listOf("#F5C84B","#67D7BA","#EB7860","#94A9F4")
    fun initialize(c:Context){context=c.applicationContext; if(config.length()==0) config=JSONObject(c.getSharedPreferences("hugo_arcade",0).getString("config","{}")!!)}
    @Synchronized fun start(p:JSONObject){
        config=JSONObject().put("mode",if(p.optInt("mode",1)==2)2 else 1).put("difficulty",p.optInt("difficulty",1).coerceIn(0,2))
            .put("arena",p.optInt("arena",0).coerceIn(0,2)).put("target",p.optInt("target",3).coerceIn(1,10)).put("mute",p.optBoolean("mute",false))
            .put("name1",p.optString("name1","PLAYER 1").take(16)).put("name2",p.optString("name2","PLAYER 2").take(16))
            .put("color1",p.optString("color1").takeIf{it in palette}?:palette[0]).put("color2",p.optString("color2").takeIf{it in palette}?:palette[1])
        context?.getSharedPreferences("hugo_arcade",0)?.edit()?.putString("config",config.toString())?.apply()
        matchId=UUID.randomUUID().toString(); tick=0; collisions=0;hits=0;roundDelay=0
        tanks.forEach{it.score=0}; slots.indices.forEach{slots[it]=Slot()}; spawn();phase="LOBBY";active=true
        if(tone==null) try{tone=ToneGenerator(AudioManager.STREAM_MUSIC,30)}catch(_:Exception){}
    }
    @Synchronized fun join(token:String,session:String,slot:Int,editor:Boolean):JSONObject {
        require(active && slot in 0..1 && (slot==0 || config.optInt("mode")==2)){"Player unavailable"}
        require(slot!=0 || editor || slots[0].token==token){"Player 1 belongs to host"}
        require(slots[1-slot].token!=token){"Use a separately paired phone for Player 2"}
        val s=slots[slot];require(s.token.isEmpty()||s.token==token){"Player slot occupied"}
        s.token=token;s.session=session;s.seq=0;s.at=SystemClock.elapsedRealtime();neutral(s)
        return state().put("player",slot+1)
    }
    @Synchronized fun input(token:String,session:String,p:JSONObject){
        require(p.optString("matchId")==matchId){"Stale match"}
        val s=slots.firstOrNull{it.token==token&&it.session==session}?:throw IllegalArgumentException("Join a player first")
        val seq=p.optLong("seq"); require(seq>s.seq){"Stale input"}
        val expiry=p.optLong("expiresAt");val now=System.currentTimeMillis();require(expiry>now&&expiry<=now+5000){"Expired input"}
        val m=p.optDouble("move",0.0);val t=p.optDouble("turn",0.0);require(m.isFinite()&&t.isFinite()&&abs(m)<=1&&abs(t)<=1){"Invalid input"}
        s.seq=seq;s.at=SystemClock.elapsedRealtime();if(phase!="RUNNING"){neutral(s);return};s.move=m;s.turn=t;s.fire=p.optBoolean("fire")
    }
    @Synchronized fun disconnect(session:String){ val owned=slots.filter{it.session==session};owned.forEach{neutral(it);it.session=""};if(owned.isNotEmpty()&&active&&phase=="RUNNING")phase="PAUSED" }
    private fun neutral(s:Slot){s.move=0.0;s.turn=0.0;s.fire=false}
    @Synchronized fun pause(){if(phase=="RUNNING")phase="PAUSED";slots.forEach{neutral(it)}}
    @Synchronized fun resume(){require(active&&phase!="FINISHED"){"Start a new match"};require(slots[0].session.isNotEmpty()&&(config.optInt("mode")==1||slots[1].session.isNotEmpty())){"Connect required players"};slots.forEach{neutral(it);it.at=SystemClock.elapsedRealtime()};phase="RUNNING"}
    @Synchronized fun exit(){active=false;phase="IDLE";slots.forEach{neutral(it)};shots.clear();tone?.release();tone=null}
    private fun spawn(){tanks[0].x=24.0;tanks[0].y=80.0;tanks[0].angle=0.0;tanks[1].x=232.0;tanks[1].y=80.0;tanks[1].angle=PI;shots.clear();cooldown.fill(0)}
    fun walls():List<RectF> = when(config.optInt("arena")){
        1->listOf(RectF(76f,38f,88f,100f),RectF(168f,62f,180f,124f))
        2->listOf(RectF(58f,42f,104f,50f),RectF(152f,110f,198f,118f),RectF(122f,62f,134f,98f))
        else->listOf(RectF(102f,36f,154f,46f),RectF(102f,114f,154f,124f))
    }
    private fun blocked(x:Double,y:Double,r:Float):Boolean = x<8+r||x>248-r||y<25+r||y>138-r||walls().any{ x+r>it.left&&x-r<it.right&&y+r>it.top&&y-r<it.bottom }
    @Synchronized override fun step(){
        if(!active||phase!="RUNNING")return
        val now=SystemClock.elapsedRealtime()
        slots.forEach{if(now-it.at>600)neutral(it)}
        if(slots[0].session.isEmpty()||now-slots[0].at>2000||(config.optInt("mode")==2&&(slots[1].session.isEmpty()||now-slots[1].at>2000))){pause();return}
        tick++
        if(roundDelay>0){roundDelay--;if(roundDelay==0)spawn();return}
        val difficulty=config.optInt("difficulty",1)
        for(i in 0..1){
            val tank=tanks[i];var move=slots[i].move;var turn=slots[i].turn;var fire=slots[i].fire
            if(i==1&&config.optInt("mode")==1){val target=atan2(tanks[0].y-tank.y,tanks[0].x-tank.x);var delta=target-tank.angle;while(delta>PI)delta-=2*PI;while(delta< -PI)delta+=2*PI;turn=delta.coerceIn(-1.0,1.0);move=if(abs(delta)<.4 && hypot(tanks[0].x-tank.x,tanks[0].y-tank.y)>70) .55 else 0.0;fire=abs(delta)<.18 && tick%(if(difficulty==0)70 else if(difficulty==1)40 else 22)==0L}
            tank.angle+=turn*.085
            val speed=.65+difficulty*.12;val nx=tank.x+cos(tank.angle)*move*speed;val ny=tank.y+sin(tank.angle)*move*speed
            if(!blocked(nx,ny,9f)&&hypot(nx-tanks[1-i].x,ny-tanks[1-i].y)>18){tank.x=nx;tank.y=ny}else if(move!=0.0){collisions++;if(i==1&&config.optInt("mode")==1)tank.angle+=.2}
            if(cooldown[i]>0)cooldown[i]--
            if(fire&&cooldown[i]==0&&shots.count{it.owner==i}<3){shots.add(Shot(tank.x+cos(tank.angle)*9,tank.y+sin(tank.angle)*9,cos(tank.angle)*2.5,sin(tank.angle)*2.5,i));cooldown[i]=18;beep(false)}
        }
        val iterator=shots.iterator();var winner=-1
        while(iterator.hasNext()){val b=iterator.next();b.x+=b.dx;b.y+=b.dy;b.life--;if(blocked(b.x,b.y,1f)||b.life<=0){iterator.remove();continue};val target=tanks[1-b.owner];if(hypot(b.x-target.x,b.y-target.y)<7){winner=b.owner;break}}
        if(winner>=0){hits++;tanks[winner].score++;beep(true);shots.clear();roundDelay=35
            if(tanks[winner].score>=config.optInt("target",3)){phase="FINISHED";slots.forEach{neutral(it)};lastMatch=JSONObject().put("config",config).put("score1",tanks[0].score).put("score2",tanks[1].score).toString();context?.getSharedPreferences("hugo_arcade",0)?.edit()?.putString("lastResult",lastMatch)?.apply()}
        }
    }
    private fun beep(hit:Boolean){if(!config.optBoolean("mute"))try{tone?.startTone(if(hit)ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP,if(hit)120 else 35)}catch(_:Exception){}}
    @Synchronized fun state():JSONObject=JSONObject().put("serverTime",System.currentTimeMillis()).put("phase",phase).put("matchId",matchId).put("config",JSONObject(config.toString())).put("tick",tick).put("fps",fps).put("collisions",collisions).put("hits",hits)
        .put("players",JSONArray(tanks.mapIndexed{i,t->JSONObject().put("x",t.x).put("y",t.y).put("angle",t.angle).put("score",t.score).put("joined",slots[i].session.isNotEmpty()).put("neutral",slots[i].move==0.0&&slots[i].turn==0.0&&!slots[i].fire)}))
        .put("lastResult",context?.getSharedPreferences("hugo_arcade",0)?.getString("lastResult",null)?.let{JSONObject(it)}?:JSONObject.NULL)
    override fun recordFps(value:Int){fps=value}
    @Synchronized override fun render(screen:Canvas,paint:Paint){
            screen.drawColor(Color.rgb(12,18,24));paint.color=Color.rgb(95,115,97)
            screen.drawRect(6f,23f,250f,25f,paint);screen.drawRect(6f,138f,250f,140f,paint);screen.drawRect(6f,23f,8f,140f,paint);screen.drawRect(248f,23f,250f,140f,paint)
            walls().forEach{screen.drawRect(it,paint)}
            for(i in 0..1){val t=tanks[i];paint.color=Color.parseColor(config.optString("color${i+1}",palette[i]));screen.save();screen.translate(t.x.toInt().toFloat(),t.y.toInt().toFloat());screen.rotate(Math.toDegrees(t.angle).toFloat());screen.drawRect(-6f,-6f,5f,-3f,paint);screen.drawRect(-6f,3f,5f,6f,paint);screen.drawRect(-4f,-3f,4f,3f,paint);screen.drawRect(0f,-1f,10f,1f,paint);screen.restore();paint.textSize=7f;screen.drawText(config.optString("name${i+1}").take(12)+" "+t.score,if(i==0)8f else 156f,12f,paint)}
            paint.color=Color.rgb(244,237,210);shots.forEach{screen.drawRect(it.x.toInt().toFloat(),it.y.toInt().toFloat(),it.x.toInt()+2f,it.y.toInt()+2f,paint)}
            if(phase!="RUNNING"){paint.color=Color.rgb(12,18,24);screen.drawRect(36f,55f,220f,104f,paint);paint.color=Color.rgb(244,237,210);paint.textAlign=Paint.Align.CENTER;paint.textSize=12f;screen.drawText("TANK DUEL",128f,70f,paint);paint.textSize=8f;screen.drawText(if(phase=="FINISHED")"PLAYER ${if(tanks[0].score>tanks[1].score)1 else 2} WINS" else phase,128f,84f,paint);paint.textSize=6f;screen.drawText("USE PHONE TO PLAY",128f,96f,paint);paint.textAlign=Paint.Align.LEFT}
        }

}

/** 256x144 bitmap, unfiltered integer scaling; fixed 30 Hz simulation, max three catch-up steps. */
class ArcadeView(context:Context,private val game:ArcadeGame=TankDuel):View(context){
    private val bitmap=Bitmap.createBitmap(256,144,Bitmap.Config.RGB_565)
    private val screen=Canvas(bitmap)
    private val paint=Paint().apply{isAntiAlias=false;isFilterBitmap=false;typeface=Typeface.MONOSPACE}
    private var last=SystemClock.uptimeMillis();private var accumulator=0L;private var sampleAt=last;private var frames=0
    override fun onDraw(canvas:Canvas){
        val now=SystemClock.uptimeMillis();accumulator+=(now-last).coerceAtMost(100);last=now
        var steps=0;while(accumulator>=33&&steps++<3){game.step();accumulator-=33}
        game.render(screen,paint)
        canvas.drawColor(Color.BLACK);val scale=min(width/256,height/144).coerceAtLeast(1);val left=(width-256*scale)/2f;val top=(height-144*scale)/2f
        canvas.drawBitmap(bitmap,null,RectF(left,top,left+256*scale,top+144*scale),paint)
        frames++;if(now-sampleAt>=1000){game.recordFps((frames*1000/(now-sampleAt)).toInt());frames=0;sampleAt=now}
        if(isAttachedToWindow&&game.active)postInvalidateDelayed(if(game.phase=="RUNNING")16 else 100)
    }
}
