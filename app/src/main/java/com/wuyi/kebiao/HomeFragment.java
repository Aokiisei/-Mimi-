package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 应用核心页面。
 * 用 WebView 承载教务系统页面，通过注入 JS 完成课表与成绩抓取；
 * 通过 FrontBridge / BackBridge 与页面 JS 双向通信；
 * 负责登录态与会话超时判定、课表缓存、天气与每日一句展示。
 */

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.google.gson.Gson;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import com.wuyi.kebiao.reminder.CourseReminderScheduler;
import com.wuyi.kebiao.widget.CourseWidgetService;

public class HomeFragment extends Fragment {
    private static final String KEY_COOKIES = "saved_cookies";
    private static final String KEY_PASSWORD = "saved_password";
    private static final String KEY_USERNAME = "saved_username";
    private static final int MAX_RETRY = 1;
    private static final String PREFS_NAME = "WuyiKebiaoPrefs";
    private static final int RETRY_DELAY_MS = 2000;
    private static final String TAG = "HomeFragment";
    private static boolean autoQueriedOnce;
    private OkHttpClient client;
    private WebView crawlerView;
    private boolean gradeAllTerms;
    private String gradeTermValue;
    private ImageView ivCharacter;
    private ImageView ivWeatherIcon;
    private LinearLayout layoutWeather;
    private ProgressDialog loginProgress;
    private SharedPreferences prefs;
    private TextView tvGreeting;
    private TextView tvMotivation;
    private TextView tvWeather;
    WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private String pendingUser = null;
    private String pendingPass = null;
    private boolean isCrawling = false;
    private boolean hasInjectedLogin = false;
    private Runnable timeoutRunnable = null;
    private int retryCount = 0;
    // 静默登录：由"有缓存的后台自动更新"触发，失败时不弹窗、不覆盖正在看的缓存课表
    private boolean pendingQuietLogin = false;
    private boolean attemptQuiet = false;
    // 本次尝试是否为静默更新：静默更新不显示登录进度百分比
    private boolean lastAttemptQuiet = false;
    // 延迟重试任务：新的登录开始时必须取消，否则旧任务会把新尝试打断
    private Runnable retryRunnable = null;
    // 尝试序号：旧的尝试迟到触发的加载/超时回调据此作废，不会干扰新一次登录
    private int crawlSeq = 0;
    private boolean bgHappened = false;
    // bgHappened 置位的时间，用于判断该标记是否"刚回前台"（仅对紧随其后的自动登录有效）
    private long bgFlagSetTime = 0L;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    // 主界面视图缓存：切走再切回时复用，避免 WebView 重新加载导致卡顿/闪白/字体闪现
    private View cachedView;
    // 页面是否已加载：五个标签页常驻，未显示的页面等真正滑到时再加载，降低启动开销
    private boolean pageLoaded;

    public void dismissLoginProgress() {
        ProgressDialog progressDialog = this.loginProgress;
        if (progressDialog != null) {
            this.loginProgress = null;
            progressDialog.dismiss();
        }
    }

    private void fetchMotivation() {
    }

    private void fetchWeather() {
    }

    public void injectGradeParser() {
        WebView webView = this.crawlerView;
        if (webView == null) {
            return;
        }
        String str = this.gradeTermValue;
        if (str == null) {
            str = HttpUrl.FRAGMENT_ENCODE_SET;
        }
        webView.evaluateJavascript("(function(){try{\nvar L=function(m){try{AndroidBack.onGradeLog(m);}catch(_){}};\nvar TERM='__TERM__';\nvar sel=document.getElementById('kksj');\nif(sel){\n  var opts=[];\n  for(var oi=0;oi<sel.options.length;oi++){\n    var op=sel.options[oi];\n    var tv=op.value||'';\n    if(tv!==''){opts.push({v:tv,t:(op.text||'').trim()});}\n  }\n  try{AndroidBack.onGradeTerms(JSON.stringify(opts));}catch(_){}\n  var ok=false;\n  for(var oj=0;oj<sel.options.length;oj++){\n    if(sel.options[oj].value===TERM){ok=true;break;}\n  }\n  if(TERM&&!ok){L('[JS] 指定学期不存在,退回全部');TERM='';}\n  sel.value=TERM;\n  L('[JS] 查询学期='+(TERM===''?'全部':TERM));\n}else{L('[JS] 未找到学期下拉kksj');}\nvar clicked=false,tries=0,expanded=false,total=0;\nfunction parseCkcj(oc){\n  var mm=oc.match(/ckcj\\s*\\(([\\s\\S]*)\\)/);\n  if(!mm)return null;\n  var args=mm[1].split(',').map(function(x){\n    return x.trim().replace(/^['\"]|['\"]$/g,'');\n  });\n  if(args.length<3)return null;\n  return {xh:args[0]||'',jx:args[1]||'',cj:args[2]||'',zcj:args[3]||''};\n}\nfunction decodeBuf(buf,ct){\n  var bytes=new Uint8Array(buf);\n  var enc='utf-8';\n  var probe=new TextDecoder('utf-8').decode(bytes.subarray(0,4096)).toLowerCase();\n  var mc=probe.match(/charset\\s*=\\s*[\"']?\\s*([a-z0-9\\-]+)/);\n  if(mc&&/gb|gb2312|gbk|gb18030/.test(mc[1]))enc='gb18030';\n  if(ct&&/charset\\s*=\\s*[^;]*gb/i.test(ct))enc='gb18030';\n  var s=new TextDecoder(enc).decode(bytes);\n  if(enc==='utf-8'){\n    var bad=0;\n    for(var k=0;k<s.length;k++){if(s.charCodeAt(k)===0xFFFD)bad++;}\n    if(bad>=3){s=new TextDecoder('gb18030').decode(bytes);enc='gb18030-auto';}\n  }\n  return {html:s,enc:enc};\n}\nvar BLACK=/^(序号|分项名称|项目名称|分项|项目|操作|说明|备注|成绩|分数|占比|比例|取得成绩|占总成绩百分比\\(?%?\\)?)$/;\nfunction unent(s){\n  return String(s).replace(/&nbsp;/gi,' ').replace(/&amp;/gi,'&').replace(/&lt;/gi,'<').replace(/&gt;/gi,'>').replace(/&quot;/gi,'\"').replace(/&#39;/gi,\"'\").replace(/&#(\\d+);/g,function(_,n){return String.fromCharCode(parseInt(n,10));});\n}\nfunction stripTags(s){\n  return unent(String(s).replace(/<[^>]*>/g,'').replace(/\\s+/g,'')).trim();\n}\nfunction collectTitles(html){\n  var titles={};\n  var objRe=/\\{[^{}]*?\\bfield\\s*:[^{}]*?\\}/g;\n  var om;\n  while((om=objRe.exec(html))){\n    var seg=om[0];\n    var fm=seg.match(/field\\s*:\\s*['\"]([A-Za-z0-9_]+)['\"]/);\n    var tm=seg.match(/title\\s*:\\s*['\"]([^'\"]{1,20})['\"]/);\n    if(fm&&tm)titles[fm[1]]=tm[1].replace(/\\s+/g,'');\n  }\n  return titles;\n}\nfunction extractBalanced(s,openIdx){\n  try{\n    var stack=[],inStr=false,q='',esc=false;\n    for(var i=openIdx;i<s.length;i++){\n      var ch=s[i];\n      if(inStr){\n        if(esc)esc=false;\n        else if(ch==='\\\\')esc=true;\n        else if(ch===q)inStr=false;\n        continue;\n      }\n      if(ch==='\"'||ch===\"'\" ){inStr=true;q=ch;continue;}\n      if(ch==='['||ch==='{')stack.push(ch);\n      else if(ch===']'){\n        if(stack.pop()!=='[')return null;\n        if(stack.length===0)return s.substring(openIdx,i+1);\n      }else if(ch==='}'){\n        if(stack.pop()!=='{')return null;\n      }\n    }\n  }catch(e){}\n  return null;\n}\nvar STD_NAMES={1:'平时成绩',2:'期中成绩',3:'期末成绩'};\nfunction parseJsonData(html,titles){\n  titles=titles||{};\n  var raw=null,how='',openIdx=-1;\n  var am=/\\b(?:let|var|const)?\\s*arr\\s*=\\s*\\[/.exec(html);\n  if(am){\n    openIdx=am.index+am[0].length-1;\n    raw=extractBalanced(html,openIdx);\n    how='arr';\n    if(!raw){raw=html.substring(openIdx,openIdx+4000);how='arr-unclosed';}\n  }\n  if(!raw){\n    var cm=/[\"']?cjxm[0-9]+[\"']?\\s*:/.exec(html);\n    if(cm){\n      var j=html.lastIndexOf('[',cm.index);\n      if(j>=0&&cm.index-j<2000){\n        var cand=extractBalanced(html,j);\n        if(cand&&/cjxm[0-9]/.test(cand)){raw=cand;how='scan';}\n      }\n    }\n  }\n  if(!raw)return {parts:[],noAssign:true};\n  var data=null,err='';\n  try{data=JSON.parse(raw);}\n  catch(e1){\n    try{data=JSON.parse(raw.replace(/,\\s*([}\\]])/g,'$1'));err='repaired';}\n    catch(e2){return {parts:[],jsonErr:String(e2.message||e2).substring(0,80),rawHead:raw.substring(0,200)};}\n  }\n  if(!Array.isArray(data))return {parts:[],jsonErr:'notarray',rawHead:raw.substring(0,200)};\n  if(!data.length)return {parts:[],empty:true,how:how};\n  var seen={},parts=[];\n  function add(name,val,pct){\n    name=String(name==null?'':name).replace(/\\s+/g,'');\n    if(!name)return;\n    var key=name+'|'+val+'|'+pct;\n    if(seen[key])return;\n    seen[key]=1;\n    var line=name;\n    if(val!==''&&val!=null)line+=' '+val;\n    if(pct)line+=' '+pct+'%';\n    parts.push(line);\n  }\n  for(var a=0;a<data.length;a++){\n    var row=data[a];\n    if(!row||typeof row!=='object')continue;\n    var idxs=[];\n    Object.keys(row).forEach(function(k){\n      var mm=k.match(/^cjxm([0-9]+)$/);\n      if(mm)idxs.push(parseInt(mm[1],10));\n    });\n    idxs.sort(function(x,y){return x-y;});\n    for(var ii=0;ii<idxs.length;ii++){\n      var i=idxs[ii],k='cjxm'+i,v=row[k];\n      var bl=row[k+'bl'];\n      var pct='';\n      if(bl!=null){\n        var pm=String(bl).match(/([\\d.]+)\\s*%?/);\n        if(pm)pct=pm[1];\n      }\n      if(v===''||v==null)continue;\n      var numV=Number(v);\n      if(isNaN(numV)){}\n      else if(numV===0&&(!pct||pct==='0'))continue;\n      var name=titles[k]||row[k+'mc']||row['xm'+i+'mc']||STD_NAMES[i]||('分项'+i);\n      add(name,v,pct);\n    }\n    if(row.zcj!=null&&row.zcj!==''){\n      add(titles['zcj']||'总评成绩',row.zcj,'');\n    }\n  }\n  return {parts:parts,count:data.length,how:how,err:err};\n}\nfunction pickRow(vals){\n  var nameIdx=-1,pctIdx=-1,numIdxs=[];\n  for(var c=0;c<vals.length;c++){\n    var v=vals[c];\n    if(!v)continue;\n    if(pctIdx<0&&/%/.test(v)){\n      var pm0=v.match(/([\\d.]+)\\s*%/);\n      if(pm0)pctIdx=c;\n    }\n    if(/^[\\d.]+$/.test(v)&&v.length<=6)numIdxs.push(c);\n    if(nameIdx<0&&/[一-龥]/.test(v)&&!BLACK.test(v)&&!/序号|分项名称|项目名称/.test(v)){\n      var cand=v.replace(/^[\\d.、\\s]+/,'');\n      if(cand&&/[一-龥]/.test(cand)&&!BLACK.test(cand))nameIdx=c;\n    }\n  }\n  if(nameIdx<0)return null;\n  var name=vals[nameIdx].replace(/^[\\d.、\\s]+/,''),score='',pct='';\n  var cands=numIdxs.filter(function(i){return i!==pctIdx;});\n  if(cands.length){\n    var pool=cands.filter(function(i){return i>nameIdx;});\n    if(!pool.length)pool=cands;\n    if(pool.length>1)pool=pool.filter(function(i){return i!==0;});\n    if(pool.length)score=vals[pool[0]];\n  }\n  if(pctIdx>=0){\n    var pm=vals[pctIdx].match(/([\\d.]+)\\s*%/);\n    if(pm)pct=pm[1];\n  }\n  return {name:name,score:score,pct:pct};\n}\nfunction emit(infos){\n  var seen={},parts=[];\n  for(var i=0;i<infos.length;i++){\n    var info=infos[i];\n    if(!info)continue;\n    var key=info.name+'|'+info.score+'|'+info.pct;\n    if(seen[key])continue;\n    seen[key]=1;\n    var line=info.name;\n    if(info.score)line+=' '+info.score;\n    if(info.pct)line+=' '+info.pct+'%';\n    parts.push(line);\n  }\n  return parts;\n}\nfunction parseDom(doc){\n  var infos=[];\n  var allRows=doc.querySelectorAll('tr');\n  var tables=doc.querySelectorAll('table');\n  var best=null,bestScore=0;\n  for(var t=0;t<tables.length;t++){\n    var tb=tables[t];\n    var trows=tb.rows||[];\n    if(trows.length<2)continue;\n    var htxt=(trows[0].textContent||'');\n    var sc=0;\n    if(/分项|项目|成绩|分数/.test(htxt))sc+=2;\n    if(/%|占比|比例/.test(htxt))sc+=2;\n    sc+=Math.min(trows.length,10);\n    if(tb.querySelector('table'))sc-=5;\n    if(sc>bestScore){bestScore=sc;best=tb;}\n  }\n  function rowInfo(r){\n    var cells=r.cells;\n    if(!cells||cells.length<2)return null;\n    if(r.querySelector('table'))return null;\n    var vals=[];\n    for(var j=0;j<cells.length;j++)vals.push(stripTags(cells[j].textContent||''));\n    return pickRow(vals);\n  }\n  if(best&&bestScore>0){\n    var brows=best.rows||[];\n    for(var i=0;i<brows.length;i++){\n      var bc=brows[i].cells;\n      if(!bc||bc.length<2)continue;\n      infos.push(rowInfo(brows[i]));\n    }\n  }\n  if(infos.filter(Boolean).length===0){\n    for(var q=0;q<allRows.length;q++){\n      var row=allRows[q];\n      if(row.querySelector('table'))continue;\n      var cs=row.cells||[];\n      if(cs.length<2){\n        cs=row.querySelectorAll('td');\n        if(cs.length<2)continue;\n      }\n      var joined=stripTags(row.textContent||'');\n      if(!/[一-龥]/.test(joined))continue;\n      if(!/\\d/.test(joined))continue;\n      if(!/(成绩|分数|%)/.test(joined))continue;\n      if(/分项名称|项目名称|序号/.test(joined))continue;\n      infos.push(rowInfo(row));\n    }\n  }\n  return {parts:emit(infos),tables:tables.length,trs:allRows.length};\n}\nfunction parseRaw(html){\n  var infos=[];\n  var trRe=/<tr[^>]*>([\\s\\S]*?)<\\/tr>/gi;\n  var tdRe=/<t[dh][^>]*>([\\s\\S]*?)<\\/t[dh]>/gi;\n  var tm;\n  while((tm=trRe.exec(html))){\n    var seg=tm[1];\n    var vals=[];\n    var dm;\n    tdRe.lastIndex=0;\n    while((dm=tdRe.exec(seg))){\n      vals.push(stripTags(dm[1]));\n    }\n    if(vals.length<2)continue;\n    var joined=vals.join('');\n    if(!/[一-龥]/.test(joined))continue;\n    if(!/\\d/.test(joined)&&!/%/.test(joined))continue;\n    if(/分项名称|项目名称/.test(joined))continue;\n    var info=pickRow(vals);\n    if(info)infos.push(info);\n  }\n  return emit(infos);\n}\nfunction pd(html){\n  try{\n    var titles=collectTitles(html);\n    var jd=parseJsonData(html,titles);\n    if(jd&&jd.parts&&jd.parts.length){\n      return {text:jd.parts.join('；'),diag:'json='+jd.count+'项 rows='+jd.parts.length+' via=json('+jd.how+(jd.err?','+jd.err:'')+') titles='+Object.keys(titles).length};\n    }\n    var doc=new DOMParser().parseFromString(html,'text/html');\n    var dom=parseDom(doc);\n    var parts=dom.parts;\n    var via='dom';\n    if(parts.length===0){\n      var raw=parseRaw(html);\n      if(raw.length){parts=raw;via='raw';}\n    }\n    if(parts.length===0){\n      var bt=doc.body?(doc.body.textContent||'').replace(/\\s+/g,' ').trim():'';\n      var frames=doc.querySelectorAll('iframe,frame');\n      var fsrcs=[];\n      for(var f=0;f<frames.length;f++){var s=frames[f].getAttribute('src');if(s)fsrcs.push(s);}\n      var extra='tables='+dom.tables+' trs='+dom.trs+' bodyLen='+bt.replace(/\\s+/g,'').length+' frames='+fsrcs.length+' via='+via+' titles='+Object.keys(titles).length;\n      if(jd){\n        if(jd.noAssign)extra+=' arr=0';\n        else if(jd.jsonErr)extra+=' jsonErr='+jd.jsonErr;\n        else if(jd.empty)extra+=' arrEmpty('+jd.how+')';\n      }\n      return {text:'',diag:extra,bodyText:bt.substring(0,800),frames:fsrcs,arrHead:jd?jd.rawHead:''};\n    }\n    return {text:parts.join('；'),diag:'rows='+parts.length+' via='+via};\n  }catch(e){return {text:'',diag:'ex='+e.message};}\n}\nvar timer=setInterval(function(){\n  tries++;\n  if(!clicked){\n    var btns=document.querySelectorAll('button,a,input');\n    for(var i=0;i<btns.length;i++){\n      var tx=(btns[i].innerText||btns[i].value||'');\n      if(tx.indexOf('查询')>=0){btns[i].click();clicked=true;L('[JS] 已点击查询按钮');break;}\n    }\n    if(!clicked&&tries==6)L('[JS] 找不到查询按钮');\n    return;\n  }\n  var body=document.querySelector('.layui-table-body table tbody');\n  var rows=body?body.querySelectorAll('tr'):[];\n  var noneEl=document.querySelector('.layui-table-none');\n  if(tries%5==0)L('[JS] 轮询'+tries+' rows='+rows.length+' none='+(noneEl?'Y':'N'));\n  if(rows.length>0){\n    if(!expanded){\n      var cnt=document.querySelector('.layui-laypage-count');\n      if(cnt){\n        var mm=(cnt.textContent||'').match(/([0-9]+)/);\n        if(mm)total=parseInt(mm[1],10);\n      }\n      L('[JS] 首批'+rows.length+'条,总数'+(total||'?'));\n      if(total>rows.length&&window.layui&&layui.table){\n        try{\n          layui.table.reload('cjcx_table',{page:{limit:1000,curr:1}});\n          expanded=true;\n          L('[JS] 已切一页1000条,等待重载');\n        }catch(e){L('[JS] 扩容失败:'+e.message);expanded=true;}\n        return;\n      }\n      expanded=true;\n    }\n    if(total>0&&rows.length<total&&tries<38)return;\n    clearInterval(timer);\n    var heads=document.querySelectorAll('.layui-table-header table thead tr th');\n    var map={};\n    for(var hd=0;hd<heads.length;hd++){map[heads[hd].innerText.trim()]=hd;}\n    var list=[];\n    for(var r=0;r<rows.length;r++){\n      var tds=rows[r].querySelectorAll('td');\n      if(tds.length<5)continue;\n      var gv=function(nm){\n        var idx=map[nm];\n        if(idx===undefined||idx>=tds.length)return '';\n        var c=tds[idx].querySelector('.layui-table-cell');\n        return (c?c.innerText:tds[idx].innerText).trim();\n      };\n      var it={term:gv('开课学期'),name:gv('课程名称'),score:gv('成绩'),credit:gv('学分'),gpa:gv('绩点')};\n      var sIdx=map['成绩'];\n      if(sIdx!==undefined&&sIdx<tds.length){\n        var a=tds[sIdx].querySelector('a[onclick*=ckcj]');\n        if(a){\n          var ck=parseCkcj(a.getAttribute('onclick')||'');\n          if(ck)it.ckcj=ck;\n        }\n      }\n      list.push(it);\n    }\n    L('[JS] 解析到'+list.length+'条,回传');\n    AndroidBack.onGrades(JSON.stringify(list));\n    var di=0;\n    function finishAll(){\n      AndroidBack.onGrades(JSON.stringify(list));\n      L('[JS] 成绩构成全部完成');\n    }\n    function fetchDetail(u,method,formData){\n      var opt={credentials:'same-origin',method:method||'GET'};\n      if(formData)opt.body=formData;\n      if(formData)opt.headers={'Content-Type':'application/x-www-form-urlencoded'};\n      return fetch(u,opt).then(function(resp){\n        var status=resp.status;\n        var ct=resp.headers.get('Content-Type')||'';\n        return resp.arrayBuffer().then(function(buf){\n          var dr=decodeBuf(buf,ct);\n          return {html:dr.html,enc:dr.enc,status:status,ct:ct};\n        });\n      });\n    }\n    function nextD(){\n      if(di>=list.length){finishAll();return;}\n      var it2=list[di];\n      if(!it2.ckcj){di++;nextD();return;}\n      if(di===0)L('[JS] 开始读取成绩构成,共'+list.length+'门');\n      var qs='xs0101id='+encodeURIComponent(it2.ckcj.xh)+'&jx0404id='+encodeURIComponent(it2.ckcj.jx)+'&cj0708id='+encodeURIComponent(it2.ckcj.cj)+'&zcj='+encodeURIComponent(it2.ckcj.zcj);\n      var url='/jsxsd/kscj/pscj_list.do?'+qs;\n      if(di<2)L('[JS] 构成参数'+di+' xh='+(it2.ckcj.xh||'空')+' jx='+(it2.ckcj.jx||'').slice(-6)+' cj='+(it2.ckcj.cj||'').slice(-6)+' zcj='+(it2.ckcj.zcj||'空'));\n      fetchDetail(url).then(function(r){\n        var meta={status:r.status,len:r.html.length,enc:r.enc,ct:r.ct};\n        var res=pd(r.html);\n        if(!res.text){\n          return fetchDetail('/jsxsd/kscj/pscj_list.do','POST',qs).then(function(r2){\n            var res2=pd(r2.html);\n            if(res2.text){res2.meta=meta;return res2;}\n            res.meta=meta;\n            res.diag=(res.diag||'')+' postLen='+r2.html.length;\n            if(res2.bodyText)res.bodyText=res2.bodyText;\n            if(res2.frames&&res2.frames.length)res.frames=res2.frames;\n            if(res2.arrHead)res.arrHead=res2.arrHead;\n            return res;\n          });\n        }\n        res.meta=meta;\n        return res;\n      }).then(function(res){\n        var mt=res.meta||{};\n        if(di===0){\n          L('[JS] 详情status='+(mt.status||'?')+' 编码='+(mt.enc||'?')+' 长度='+(mt.len||'?')+' '+(res.diag||''));\n          if(res.arrHead)L('[JS] arrHead='+res.arrHead);\n          if(res.bodyText)L('[JS] bodyText='+res.bodyText);\n          if(res.frames&&res.frames.length)L('[JS] frames='+res.frames.join('|'));\n          L('[JS] 构成0结果='+(res.text?res.text.substring(0,120):'空'));\n        }\n        if(res.text)it2.detail=res.text;\n        if(di%10===9)L('[JS] 成绩构成进度'+(di+1)+'/'+list.length);\n        di++;\n        setTimeout(nextD,80);\n      }).catch(function(e){\n        if(di===0)L('[JS] 详情请求异常:'+e.message);\n        di++;\n        setTimeout(nextD,80);\n      });\n    }\n    nextD();\n    return;\n  }\n  if(noneEl){\n    clearInterval(timer);\n    L('[JS] 教务返回无数据');\n    AndroidBack.onGrades('[]');\n    return;\n  }\n  if(tries>=45){\n    clearInterval(timer);\n    L('[JS] 等待超时');\n    AndroidBack.onGrades('[]');\n  }\n},500);\n}catch(e){try{AndroidBack.onGradeLog('[JS] 异常:'+e.message);}catch(_){}AndroidBack.onGrades('[]');}})();\n".replace("__TERM__", str), null);
        notifyGradeLog("[A] GRADE_JS 已注入成绩页");
    }

    public void loadCrawlerEntry() {
        this.crawlerView.stopLoading();
        this.crawlerView.clearHistory();
        this.crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1");
    }

    public void notifyFrontGrades(String str) {
        notifyFrontProgress(100, "查询完成");
        String strReplace = str.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("'", "\\'");
        WebView webView = this.webView;
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.receiveFromAndroid('grades', '" + strReplace + "');", null);
    }

    public void notifyGradeLog(String str) {
        String strReplace = str.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("'", "\\'");
        WebView webView = this.webView;
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.gradeLog&&gradeLog('" + strReplace + "');", null);
    }

    public void notifyGradeTerms(String str) {
        String strReplace = str.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("'", "\\'");
        WebView webView = this.webView;
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.receiveFromAndroid('grade_terms', '" + strReplace + "');", null);
    }

    public void notifyTimetableLog(String str) {
        String strReplace = str.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("'", "\\'");
        WebView webView = this.webView;
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.timetableLog&&timetableLog('" + strReplace + "');", null);
    }

    public void startGradeCrawl() {
        this.isCrawling = true;
        WebView webView = this.crawlerView;
        if (webView == null) {
            return;
        }
        webView.stopLoading();
        this.crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/kscj/cjcx_frm");
        notifyGradeLog("[A] 开始加载成绩页 cjcx_frm");
        notifyFrontProgress(90, "正在读取成绩…");
        Runnable runnable = this.timeoutRunnable;
        if (runnable != null) {
            this.mainHandler.removeCallbacks(runnable);
            this.mainHandler.postDelayed(runnable, 30000L);
        }
    }

    public boolean isGradeMode() {
        return false;
    }

    public void setGradeAllTerms(boolean z) {
        this.gradeAllTerms = z;
    }

    public void setGradeTermValue(String str) {
        this.gradeTermValue = str;
    }

    @Override
    public View onCreateView(LayoutInflater layoutInflater, ViewGroup viewGroup, Bundle bundle) {
        this.isCrawling = false;
        this.hasInjectedLogin = false;
        this.retryCount = 0;
        this.pendingUser = null;
        this.pendingPass = null;
        // 切回主界面时复用上次的视图，避免 WebView 重新加载造成卡顿、闪烁与字体闪现
        if (this.cachedView != null) {
            ViewGroup oldParent = (ViewGroup) this.cachedView.getParent();
            if (oldParent != null) {
                oldParent.removeView(this.cachedView);
            }
            // 抓取用的隐藏 WebView 随上个视图销毁了，这里重新创建一个
            WebView crawler = new WebView(requireContext());
            crawler.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
            crawler.setVisibility(8);
            ((ViewGroup) this.cachedView).addView(crawler);
            this.crawlerView = crawler;
            setupCrawlerView();
            return this.cachedView;
        }
        View viewInflate = layoutInflater.inflate(R.layout.fragment_home, viewGroup, false);
        this.prefs = requireActivity().getSharedPreferences(PREFS_NAME, 0);
        this.client = new OkHttpClient();
        // 进程被杀后重进：读取上次驻留后台的时间戳，判断教务会话是否可能已超时
        checkBackgroundTimeout();
        this.webView = (WebView) viewInflate.findViewById(R.id.webView);
        this.webView.setBackgroundColor(0xFFF5F4ED);
        initHomeUI(viewInflate);
        ((View) this.tvGreeting.getParent()).setBackgroundColor(ThemeH.primary);
        WebView webView = new WebView(requireContext());
        this.crawlerView = webView;
        webView.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        this.crawlerView.setVisibility(8);
        ((ViewGroup) viewInflate).addView(this.crawlerView);
        setupWebView(this.webView);
        loadPageOnce();
        setupCrawlerView();
        this.cachedView = viewInflate;
        return viewInflate;
    }

    /** 首次可见时才加载页面；之后切回不再重复加载 */
    private void loadPageOnce() {
        if (this.pageLoaded || this.webView == null) {
            return;
        }
        this.pageLoaded = true;
        this.webView.loadUrl(isGradeMode() ? "file:///android_asset/www/grade.html" : "file:///android_asset/www/index.html");
    }

    @Override
    public void onPause() {
        super.onPause();
        // 离开课表页时停止多页卡片自动轮播，节省资源
        try { if (this.webView != null) this.webView.evaluateJavascript("window.stopCarousel&&window.stopCarousel();", null); } catch (Throwable th) {}
        // 记录离开前台的时间戳并持久化：进程被杀后重进也能判断后台驻留时长
        if (this.prefs != null) {
            this.prefs.edit().putLong(BG_AT_KEY, System.currentTimeMillis()).apply();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (this.prefs != null) {
            this.prefs.edit().putLong(BG_AT_KEY, System.currentTimeMillis()).apply();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 滑到本页时才真正加载网页（首屏即当前页时无延迟）
        loadPageOnce();
        // 回到课表页时按最新设置恢复自动轮播
        try { if (this.webView != null) this.webView.evaluateJavascript("window.applyCarousel&&window.applyCarousel();", null); } catch (Throwable th) {}
        // 日程页可能把日程标记为已完成，回到课表时重新载入并按当前视图刷新，避免已完成的日程残留
        try { if (this.webView != null) this.webView.evaluateJavascript("window.refreshSchedules&&window.refreshSchedules();", null); } catch (Throwable th) {}
        // 进程存活时回到前台：若后台驻留超过阈值，标记"会话可能已超时"
        checkBackgroundTimeout();
    }

    // 后台驻留超过该时长即视为教务会话可能已超时（毫秒）
    static final long BG_TIMEOUT_MS = 10 * 60 * 1000L;
    static final String BG_AT_KEY = "mimi_bg_at";

    private void checkBackgroundTimeout() {
        try {
            if (this.prefs == null) {
                return;
            }
            long bgAt = this.prefs.getLong(BG_AT_KEY, 0L);
            if (bgAt > 0 && System.currentTimeMillis() - bgAt > BG_TIMEOUT_MS) {
                this.bgHappened = true;
                this.bgFlagSetTime = System.currentTimeMillis();
                Log.d(TAG, "检测到长时间后台驻留，标记会话可能超时");
            }
            this.prefs.edit().remove(BG_AT_KEY).apply();
        } catch (Throwable th) {
        }
    }

    public boolean isOnLoginPage() {
        try {
            String url = this.crawlerView != null ? this.crawlerView.getUrl() : null;
            if (url != null && url.contains("sso.wuyiu.edu.cn")) {
                return true;
            }
        } catch (Throwable th) {
        }
        return false;
    }

    // 用于"页面文本"的账密错误判定：只认教务明确返回的错误措辞。
    // 不能把「账号或密码」「用户名或密码」这种短语算进来：SSO 登录页的标签页本身就是
    // 「用户名密码登录」，这样写会把"还停在登录页/超时"直接判成账密错误。
    private static boolean isCredentialErrorInPage(String str) {
        if (str == null) {
            return false;
        }
        return str.contains("密码错误") || str.contains("密码不正确")
                || str.contains("密码输入错误") || str.contains("密码有误")
                || str.contains("账号不存在") || str.contains("用户不存在");
    }

    // 教务提示"错误次数过多/账号被锁定"：与密码错误区分开，避免用户一直重试把锁定时间拖长
    private static boolean isLockedInPage(String str) {
        if (str == null) {
            return false;
        }
        return str.contains("锁定") || str.contains("次数过多") || str.contains("频繁")
                || str.contains("暂时无法登录");
    }

    public static boolean isCredentialError(String str) {
        if (str == null) {
            return false;
        }
        // App 自己上报的流程错误（页面没加载出来/找不到按钮/登录页超时）不是教务的账密提示，
        // 不能当成"账号密码错误"，否则用户把密码改对了之后还会看到同样的误导提示
        if (str.startsWith("超时：") || str.startsWith("登录页加载超时")
                || str.contains("未找到登录按钮") || str.contains("未找到账号输入框")) {
            return false;
        }
        return str.contains("密码错误") || str.contains("密码不正确") || str.contains("密码输入错误")
                || str.contains("密码有误") || str.contains("账号不存在") || str.contains("用户不存在")
                || str.contains("验证码");
    }

    private void initHomeUI(View view) {
        this.tvGreeting = (TextView) view.findViewById(R.id.tv_greeting);
        this.tvWeather = (TextView) view.findViewById(R.id.tv_weather);
        this.ivWeatherIcon = (ImageView) view.findViewById(R.id.iv_weather_icon);
        this.layoutWeather = (LinearLayout) view.findViewById(R.id.layout_weather);
        this.ivCharacter = (ImageView) view.findViewById(R.id.iv_character);
        this.tvMotivation = (TextView) view.findViewById(R.id.tv_motivation);
        updateGreeting();
        fetchWeather();
        fetchMotivation();
        this.layoutWeather.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                onWeatherClicked(view2);
            }
        });
    }

    void onWeatherClicked(View view) {
        fetchWeather();
    }

    private void updateGreeting() {
        String str;
        int i = Calendar.getInstance().get(11);
        if (i >= 5 && i < 11) {
            str = "开启新的一天";
        } else if (i >= 11 && i < 13) {
            str = "午睡啦";
        } else if (i >= 13 && i < 18) {
            str = "精力充沛";
        } else if (i >= 18 && i < 22) {
            str = "可以放松啦";
        } else {
            str = "难道你是夜猫子";
        }
        this.tvGreeting.setText(str);
    }

    public void updateCharacterImage(String str) {
        final int i;
        if (str.contains("晴")) {
            i = R.drawable.character_sunny;
        } else if (str.contains("雨")) {
            i = R.drawable.character_rainy;
        } else if (str.contains("阴") || str.contains("云")) {
            i = R.drawable.character_cloudy;
        } else {
            i = R.drawable.character_default;
        }
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public final void run() {
                setCharacterImage(i);
            }
        });
    }

    void setCharacterImage(int i) {
        this.ivCharacter.setImageResource(i);
    }

    private void setupWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setCacheMode(2);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (HomeFragment.this.customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                HomeFragment.this.customView = view;
                HomeFragment.this.customViewCallback = callback;
                ViewGroup decor = (ViewGroup) HomeFragment.this.requireActivity().getWindow().getDecorView();
                decor.addView(view, new ViewGroup.LayoutParams(-1, -1));
                HomeFragment.this.webView.setVisibility(8);
            }

            @Override
            public void onHideCustomView() {
                if (HomeFragment.this.customView == null) {
                    return;
                }
                ViewGroup decor = (ViewGroup) HomeFragment.this.requireActivity().getWindow().getDecorView();
                decor.removeView(HomeFragment.this.customView);
                HomeFragment.this.customView = null;
                HomeFragment.this.webView.setVisibility(0);
                HomeFragment.this.customViewCallback.onCustomViewHidden();
            }
        });
        webView.addJavascriptInterface(new FrontBridge(), "Android");
    }

    private void setupCrawlerView() {
        WebSettings settings = this.crawlerView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(2);
        this.crawlerView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(HomeFragment.TAG, "WebView Console: " + consoleMessage.message());
                return true;
            }
        });
        this.crawlerView.setWebViewClient(new CrawlerClient());
        this.crawlerView.addJavascriptInterface(new BackBridge(), "AndroidBack");
    }

    private void autoLoginIfCredentialsExist() {
        if (isGradeMode() || autoQueriedOnce) {
            return;
        }
        autoQueriedOnce = true;
        final String string = this.prefs.getString(KEY_USERNAME, null);
        final String string2 = this.prefs.getString(KEY_PASSWORD, null);
        if (string == null || string2 == null || string.isEmpty() || string2.isEmpty()) {
            return;
        }
        this.webView.postDelayed(new Runnable() {
            @Override
            public final void run() {
                doAutoLogin(string, string2);
            }
        }, 1000L);
    }

    void doAutoLogin(String str, String str2) {
        this.pendingQuietLogin = true; // 自动登录属于后台行为：失败不弹窗
        this.pendingUser = str;
        this.pendingPass = str2;
        startCrawl();
    }

    public class FrontBridge {
        @JavascriptInterface
        public String getGlassUi() {
            String str = ThemeH.glass;
            return str != null ? str : "0";
        }

        @JavascriptInterface
        public String getAutoCarousel() {
            String str = ThemeH.autoCarousel;
            return str != null ? str : "0";
        }

        @JavascriptInterface
        public String getTheme() {
            return ThemeH.name;
        }

        @JavascriptInterface
        public void queryGrades() {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.setGradeAllTerms(true);
            homeFragment.mainHandler.post(new StartGradeRunnable(homeFragment));
        }

        @JavascriptInterface
        public final void queryGradesCurrent() {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.setGradeAllTerms(false);
            homeFragment.mainHandler.post(new StartGradeRunnable(homeFragment));
        }

        @JavascriptInterface
        public final void queryGradesTerm(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.setGradeTermValue(str);
            homeFragment.mainHandler.post(new StartGradeRunnable(homeFragment));
        }

        @JavascriptInterface
        public void saveCredentials(String str, String str2) {
            HomeFragment.this.prefs.edit().putString(HomeFragment.KEY_USERNAME, str).putString(HomeFragment.KEY_PASSWORD, str2).apply();
            Log.d(HomeFragment.TAG, "已保存成绩页账号密码");
        }

        // 课表页点跨格日程 → 切到日程页
        @JavascriptInterface
        public void openScheduleTab() {
            try {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ((MainActivity) getActivity()).pager.setCurrentItem(1, true);
                            } catch (Throwable ignore) {}
                        }
                    });
                }
            } catch (Throwable ignore) {}
        }

        // 前端算出当前教学周后回传，供桌面小组件显示"第X/18周"
        @JavascriptInterface
        public void setWidgetWeek(String str) {
            try {
                final int w = Integer.parseInt(str);
                if (w <= 0) {
                    return;
                }
                HomeFragment.this.prefs.edit().putInt("mimi_week_no", w).apply();
                Context ctx = HomeFragment.this.getContext();
                if (ctx == null) {
                    return;
                }
                CourseWidgetService.saveWeekNo(ctx, w);
                CourseWidgetService.saveTotalWeek(ctx, Math.max(CourseWidgetService.DEFAULT_TOTAL_WEEK, w));
                CourseWidgetService.refreshAll(ctx);
                // 教学周变化后重排上课提醒，避免用旧周次匹配课程
                CourseReminderScheduler.reschedule(ctx);
            } catch (Exception e) {
                Log.w(HomeFragment.TAG, "回传教学周失败: " + str);
            }
        }

        @JavascriptInterface
        public void setTheme(String str) {
            ThemeH.save(HomeFragment.this.requireContext(), str);
        }

        public FrontBridge() {
        }

        @JavascriptInterface
        public void login(String str, String str2) {
            Log.d(HomeFragment.TAG, "前台请求登录: " + str);
            HomeFragment.this.pendingQuietLogin = false;
            HomeFragment.this.pendingUser = str;
            HomeFragment.this.pendingPass = str2;
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    HomeFragment.this.startCrawl();
                }
            });
        }

        /** 自动登录（静默）：开屏带缓存的后台更新走这里，失败时不弹窗、不打断正在看的课表 */
        @JavascriptInterface
        public void loginAuto(String str, String str2) {
            Log.d(HomeFragment.TAG, "前台请求自动登录（静默）: " + str);
            HomeFragment.this.pendingQuietLogin = true;
            HomeFragment.this.pendingUser = str;
            HomeFragment.this.pendingPass = str2;
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    HomeFragment.this.startCrawl();
                }
            });
        }

        @JavascriptInterface
        public void refresh() {
            doRefresh(false);
        }

        /** 30 分钟定时自动更新走这里：失败同样静默处理 */
        @JavascriptInterface
        public void refreshAuto() {
            doRefresh(true);
        }

        private void doRefresh(boolean quiet) {
            HomeFragment.this.pendingQuietLogin = quiet;
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.pendingUser = homeFragment.prefs.getString(HomeFragment.KEY_USERNAME, null);
            HomeFragment homeFragment2 = HomeFragment.this;
            homeFragment2.pendingPass = homeFragment2.prefs.getString(HomeFragment.KEY_PASSWORD, null);
            if (HomeFragment.this.pendingUser == null) {
                HomeFragment.this.notifyFrontError("请先输入账号密码登录");
                return;
            }
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    HomeFragment.this.startCrawl();
                }
            });
        }

        @JavascriptInterface
        public void relogin() {
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    HomeFragment.this.clearAllData();
                    HomeFragment.this.webView.loadUrl(HomeFragment.this.isGradeMode() ? "file:///android_asset/www/grade.html" : "file:///android_asset/www/index.html");
                }
            });
        }

        @JavascriptInterface
        public void clearSavedCredentials() {
            // 只清保存的教务凭据与教务 Cookie。
            // 快递身份码（菜鸟/淘宝/拼多多/京东）在小助手里是独立登录态，
            // 不受清空账号、清空缓存等操作影响，所以这里不能再 removeAllCookies。
            HomeFragment.this.prefs.edit()
                    .remove(HomeFragment.KEY_USERNAME)
                    .remove(HomeFragment.KEY_PASSWORD)
                    .remove(HomeFragment.KEY_COOKIES)
                    .apply();
            clearJwLoginCookies();
            Log.d(HomeFragment.TAG, "已清除保存的账号密码与教务登录状态");
        }

        /**
         * 仅清除保存的教务账号与教务会话。
         * 用于「记住密码未勾选」等自动场景，与 clearSavedCredentials 一样只作用于教务，
         * 避免把菜鸟/淘宝/拼多多/京东等快递身份码的登录态一起丢失。
         */
        @JavascriptInterface
        public void clearSavedCredentialsOnly() {
            HomeFragment.this.prefs.edit()
                    .remove(HomeFragment.KEY_USERNAME)
                    .remove(HomeFragment.KEY_PASSWORD)
                    .remove(HomeFragment.KEY_COOKIES)
                    .apply();
            clearJwLoginCookies();
            Log.d(HomeFragment.TAG, "已清除保存的教务账号与教务会话");
        }

        @JavascriptInterface
        public void getSavedCredentials() {
            String string = HomeFragment.this.prefs.getString(HomeFragment.KEY_USERNAME, null);
            String string2 = HomeFragment.this.prefs.getString(HomeFragment.KEY_PASSWORD, null);
            if (string == null || string.isEmpty()) {
                HomeFragment.this.mainHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        deliverNoSavedCredentials();
                    }
                });
                return;
            }
            StringBuilder sb = new StringBuilder("{\"username\":\"");
            sb.append(string);
            sb.append("\",\"password\":\"");
            if (string2 == null) {
                string2 = HttpUrl.FRAGMENT_ENCODE_SET;
            }
            sb.append(string2);
            sb.append("\"}");
            final String string3 = sb.toString();
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    deliverSavedCredentials(string3);
                }
            });
        }

        void deliverSavedCredentials(String str) {
            HomeFragment.this.webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', '" + str + "');", null);
        }

        void deliverNoSavedCredentials() {
            HomeFragment.this.webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', 'null');", null);
        }

        @JavascriptInterface
        public String getSchedules() {
            return ScheduleStore.loadJson(HomeFragment.this.requireContext());
        }

        @JavascriptInterface
        public void saveSchedule(String str) {
            ScheduleStore.save(HomeFragment.this.requireContext(), str);
        }

        @JavascriptInterface
        public void deleteSchedule(String str) {
            ScheduleStore.delete(HomeFragment.this.requireContext(), str);
        }

        @JavascriptInterface
        public void forceRetry() {
            Log.d(HomeFragment.TAG, "手动强制重试");
            HomeFragment.this.retryCount = 0;
            HomeFragment.this.startCrawl();
        }
    }

    public void startCrawl() {
        // 新的登录请求接管上一次没跑完的尝试：不取消旧超时/旧重试，
        // 用户改对账密后的新登录会被旧任务打断，看起来就像"密码还是错的"
        if (this.isCrawling) {
            Log.d(TAG, "收到新的登录请求，中止上一次未完成的抓取");
            cancelPendingRetry();
            cancelLoginTimeout();
            try {
                if (this.crawlerView != null) {
                    this.crawlerView.stopLoading();
                }
            } catch (Throwable th) {
            }
        }
        this.isCrawling = true;
        this.hasInjectedLogin = false;
        this.retryCount = 0; // 用户重新登录：重试预算重新计算
        this.attemptQuiet = this.pendingQuietLogin;
        this.pendingQuietLogin = false;
        // 手动登录：重建抓取 WebView。上一轮失败（尤其是账密错误）留在页面会话里的
        // linkid/失败计数清 Cookie 清不掉，重建后才和"杀掉 App 重进"一样干净
        if (!this.attemptQuiet) {
            resetCrawlerSession();
        }
        this.lastAttemptQuiet = this.attemptQuiet;
        Log.d(TAG, "登录尝试开始: 用户=" + this.pendingUser + " 密码长度="
                + (this.pendingPass == null ? -1 : this.pendingPass.length())
                + " quiet=" + this.attemptQuiet + " seq=" + (this.crawlSeq + 1));
        // "曾切后台"标记只对"刚回前台时立刻触发的那次自动登录"有意义。
        // 若该标记置位已超过 10 秒，说明是用户回到前台后又手动发起的登录（例如手输错密码），
        // 此时必须清除，否则本次登录超时会被误判为"登录超时"。
        if (this.bgHappened && this.bgFlagSetTime > 0L
                && System.currentTimeMillis() - this.bgFlagSetTime > 10000L) {
            Log.d(TAG, "后台标记已过期，按普通登录处理");
            this.bgHappened = false;
            this.bgFlagSetTime = 0L;
        }
        this.webView.evaluateJavascript("document.getElementById('login-page').classList.add('active');document.getElementById('timetable-page').classList.remove('active');var btn=document.querySelector('.btn .txt');if(btn)btn.textContent='登录中...';var spin=document.querySelector('.btn .loading');if(spin)spin.classList.remove('hidden');var lp=document.getElementById('loginProgress');if(lp)lp.classList.remove('hidden');", null);
        Log.d(TAG, "后台清除旧Cookie，重新登录...");
        armLoginTimeout();
        notifyFrontProgress(10, "正在连接教务系统…");
        clearCookiesThenLoad(++this.crawlSeq);
    }

    /** 登录阶段的超时：只兜底"登录"本身（含 SSO 跳转），进入教务主页后由解析超时接管。
     *  20 秒：账密错误时教务一般 2-4 秒就会返回提示，超时只做最坏情况的兜底。 */
    private void armLoginTimeout() {
        cancelLoginTimeout();
        Runnable runnable = new Runnable() {
            @Override
            public final void run() {
                onLoginTimeout();
            }
        };
        this.timeoutRunnable = runnable;
        this.mainHandler.postDelayed(runnable, 20000L);
    }

    private void cancelLoginTimeout() {
        if (this.timeoutRunnable != null) {
            this.mainHandler.removeCallbacks(this.timeoutRunnable);
            this.timeoutRunnable = null;
        }
    }

    private void cancelPendingRetry() {
        if (this.retryRunnable != null) {
            this.mainHandler.removeCallbacks(this.retryRunnable);
            this.retryRunnable = null;
        }
    }

    /** 延迟自动重试：保留引用，新的登录开始时能取消掉 */
    private void scheduleRetry(long delayMs) {
        cancelPendingRetry();
        Runnable runnable = new Runnable() {
            @Override
            public final void run() {
                retryRunnable = null;
                retryCrawl();
            }
        };
        this.retryRunnable = runnable;
        this.mainHandler.postDelayed(runnable, delayMs);
    }

    /**
     * 先清空教务相关 Cookie、再加载教务入口页。
     * 注意：不能再用 removeAllCookies——它是整个 App 共享的 Cookie 存储，
     * 会把快递身份码（淘宝/菜鸟/拼多多）等其他登录态一起清掉。
     * 这里只按教务域名逐个过期，保证只重置教务 SSO 会话。
     */
    private void clearCookiesThenLoad(final int seq) {
        final boolean[] started = new boolean[]{false};
        final Runnable loadOnce = new Runnable() {
            @Override
            public void run() {
                // 迟到的旧尝试回调直接丢弃，避免两次登录互相打断
                if (started[0] || !isCrawling || seq != crawlSeq) {
                    return;
                }
                started[0] = true;
                try {
                    if (crawlerView != null) {
                        crawlerView.clearFormData();
                    }
                } catch (Throwable th) {
                }
                mainHandler.postDelayed(new StartLoadRunnable(HomeFragment.this), 0L);
            }
        };
        try {
            clearJwLoginCookies();
        } catch (Throwable th) {
        }
        // CookieManager.setCookie 是异步落盘的，留一点时间再开始加载
        this.mainHandler.postDelayed(loadOnce, 250L);
    }

    /**
     * 只过期教务系统（jwxt / sso）的 Cookie，保留 App 内其他站点（快递身份码等）的登录态。
     * Cookie 可能挂在根路径、jsxsd、authserver 等不同 path 上，按常见 path 与域名逐一生效。
     */
    private void clearJwLoginCookies() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String[] probes = {
                    "https://jwxt.wuyiu.edu.cn/",
                    "https://jwxt.wuyiu.edu.cn/jsxsd/",
                    "https://sso.wuyiu.edu.cn/",
                    "https://sso.wuyiu.edu.cn/authserver/"
            };
            String[] paths = {"/", "/jsxsd", "/jsxsd/", "/authserver", "/authserver/"};
            String expire = "=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=";
            for (String probe : probes) {
                String raw = cookieManager.getCookie(probe);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                for (String item : raw.split(";")) {
                    String pair = item.trim();
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = pair.substring(0, eq).trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    for (String path : paths) {
                        cookieManager.setCookie(probe, name + expire + path);
                        cookieManager.setCookie(probe, name + expire + path + "; domain=.wuyiu.edu.cn");
                    }
                }
            }
            cookieManager.flush();
        } catch (Throwable th) {
            Log.e(TAG, "清除教务 Cookie 失败: " + th.getMessage());
        }
    }

    public void onLoginTimeout() {
        final int seq = this.crawlSeq;
        if (this.isCrawling) {
            Log.e(TAG, "登录超时，终止登录");
            this.isCrawling = false;
            this.hasInjectedLogin = false;
            final WebView webView = this.crawlerView;
            if (webView != null) {
                webView.stopLoading();
            }
            dismissLoginProgress();
            final boolean wasInBg = this.bgHappened;
            this.bgHappened = false;
            this.bgFlagSetTime = 0L;
            // 关键判据：直接读取爬虫页面文本，看教务是否真的返回了账号/密码错误提示。
            // 有提示 → 账密错误；没有明确提示 → 一律按"超时"处理，不能凭"还停在登录页"就断定密码错。
            if (webView != null) {
                webView.evaluateJavascript(READ_PAGE_TEXT_JS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        // 回调是异步的：若用户这时已经发起新的登录，旧的超时结果直接丢弃
                        if (seq != crawlSeq) {
                            return;
                        }
                        finishLoginFailure(jsonToText(value), wasInBg);
                    }
                });
            } else {
                finishLoginFailure("", wasInBg);
            }
        }
    }

    // 读取爬虫页面上的错误/提示文本。
    // 上一次失败留下的旧提示（data-mimi-stale）必须排除，否则超时会读到上一轮的
    // 「密码错误」把本次登录误判成账密错误。
    private static final String READ_PAGE_TEXT_JS =
            "(function(){try{var t='';"
            + "var sels=['.ant-alert-message','.ant-alert-error','.ant-form-item-explain-error','.ant-message-notice','#msg','#errorMsg','.login-error','.error','.err'];"
            + "for(var i=0;i<sels.length;i++){var ns=document.querySelectorAll(sels[i]);for(var j=0;j<ns.length;j++){var e=ns[j];if(e.getAttribute('data-mimi-stale'))continue;var x=(e.innerText||'').trim();if(x){t+=x+' ';}}}"
            + "if(!t&&document.body){var c=document.body.cloneNode(true);var ss=c.querySelectorAll('[data-mimi-stale]');for(var k=0;k<ss.length;k++){if(ss[k].parentNode)ss[k].parentNode.removeChild(ss[k]);}t=(c.textContent||'').replace(/\\s+/g,' ');}"
            + "return t.substring(0,1200);}catch(x){return '';}})()";

    // evaluateJavascript 返回的是 JSON 字符串，做基本反转义后用于关键词匹配
    private static String jsonToText(String json) {
        if (json == null) {
            return "";
        }
        String s = json.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\n", "\n").replace("\\r", " ").replace("\\t", " ")
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&");
    }

    /**
     * 把抓取用的 WebView 整个重建，并清空 WebView 级的页面存储。
     * 为什么必须重建：账密错误后 SSO 会把 linkid/失败计数等留在 sessionStorage 里，
     * 只清 Cookie 页面会话还在，重新输入正确密码仍然会被判错——效果等同于"杀掉 App 重进"。
     */
    private void resetCrawlerSession() {
        try {
            WebView old = this.crawlerView;
            ViewGroup parent = old != null ? (ViewGroup) old.getParent() : null;
            if (old != null && parent != null) {
                old.stopLoading();
                parent.removeView(old);
                WebView crawler = new WebView(requireContext());
                crawler.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
                crawler.setVisibility(8);
                parent.addView(crawler);
                this.crawlerView = crawler;
                setupCrawlerView();
                old.destroy();
                Log.d(TAG, "已重建爬虫 WebView（清空页面内会话）");
            }
        } catch (Throwable th) {
            Log.e(TAG, "重建爬虫 WebView 失败: " + th.getMessage());
        }
        // 注意：不能用 WebStorage.deleteAllData()，它是 App 全局的，
        // 会把主界面 localStorage 里的"记住密码/自动登录/课表记忆"勾选状态一起清掉。
        // 页面级 sessionStorage 已经随 WebView 重建失效，这里只清表单/HTTP 认证残留。
        try {
            WebViewDatabase db = WebViewDatabase.getInstance(requireContext());
            db.clearFormData();
            db.clearHttpAuthUsernamePassword();
        } catch (Throwable th) {
        }
    }

    /** 登录明确失败（账号或密码错误）：清掉账号/密码/Cookie 与课表缓存，回到登录页让用户重新输入 */
    private void clearAuthStateAndCache(final String tipMsg) {
        this.pendingUser = null;
        this.pendingPass = null;
        try {
            this.prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).remove(KEY_COOKIES).apply();
            Log.d(TAG, "账密错误：已清除保存的账号密码与 Cookie");
        } catch (Throwable th) {
        }
        // 重建抓取 WebView（等同杀掉 App 重进），否则 SSO 页面会话里的失败状态会一直残留
        resetCrawlerSession();
        try {
            // 只清教务 Cookie，快递身份码等其他站点的登录态不能跟着丢
            clearJwLoginCookies();
        } catch (Throwable th) {
        }
        // 桌面小组件的课表缓存也清掉，避免登录页已清空但桌面还挂着旧课表
        try {
            Context context = getContext();
            if (context != null) {
                CourseWidgetService.saveCourses(context, "[]");
                CourseWidgetService.refreshAll(context);
                CourseReminderScheduler.reschedule(context);
            }
        } catch (Throwable th) {
        }
        WebView webView = this.webView;
        if (webView == null) {
            return;
        }
        String msg = tipMsg == null ? "" : tipMsg.replace("\\", "\\\\").replace("'", "\\'");
        // 前端负责清课表缓存、清空输入框、切回登录页
        webView.evaluateJavascript("window.mimiForceRelogin&&window.mimiForceRelogin('" + msg + "');", null);
    }

    // 登录收尾：只有教务页面真的返回了账密错误提示才算密码错；其余一律按超时处理
    public void finishLoginFailure(String pageText, boolean wasInBg) {
        boolean locked = isLockedInPage(pageText);
        boolean credErr = isCredentialErrorInPage(pageText);
        final String tipText;
        final String dlgTitle;
        if (locked) {
            // 教务提示"错误次数过多/账号锁定"：别再让用户连续试，越试锁定越久
            tipText = "账号可能已被临时锁定，请等 5-10 分钟后再登录";
            dlgTitle = "登录失败";
        } else if (credErr) {
            // 教务确实返回了账密错误提示（含手输错密码）
            tipText = "账号或密码输入错误，请重新输入";
            dlgTitle = "登录失败";
        } else if (wasInBg) {
            // 长时间后台导致会话失效
            tipText = "登录超时，请返回主界面重新登录即可";
            dlgTitle = "登录超时";
        } else {
            // 页面没有明确错误提示：绝不能凭"还停在登录页"就判定成密码错误
            tipText = "登录超时，请重试";
            dlgTitle = "登录超时";
        }
        Log.d(TAG, "登录收尾: credErr=" + credErr + " locked=" + locked + " wasInBg=" + wasInBg
                + " 页面文本=" + pageText);
        final boolean quiet = this.attemptQuiet;
        this.attemptQuiet = false;
        if (quiet) {
            // 后台自动更新失败：不弹窗、不覆盖已显示的缓存课表，只在日志里留一条
            Log.d(TAG, "后台自动更新失败（静默）: " + tipText);
            notifyFrontStatus("自动更新失败：" + tipText);
            return;
        }
        // 账密明确错误：清空账号/密码/课表缓存并回到登录页，让用户重新输入
        // （锁定不算在内：锁定期间账密可能是对的，清掉反而让用户没法直接重试）
        if (credErr && !locked) {
            clearAuthStateAndCache("账号或密码输入错误，请重新输入");
        }
        notifyFrontError(tipText);
        try {
            Context context = getContext();
            if (context != null) {
                MimiDialogBuilder builder = new MimiDialogBuilder(context);
                builder.setTitle(dlgTitle);
                builder.setMessage(tipText);
                builder.setPositiveButton("好的", (DialogInterface.OnClickListener) null);
                builder.show();
            }
        } catch (Throwable th) {
        }
    }

    public void retryCrawl() {
        Log.d(TAG, "执行重试，当前重试次数: " + this.retryCount);
        this.isCrawling = true;
        this.hasInjectedLogin = false;
        // 重试也必须重新武装超时：否则页面卡住时 isCrawling 会一直是 true，
        // 之后用户手动输入正确账密点登录会被这个状态直接吞掉
        armLoginTimeout();
        clearCookiesThenLoad(++this.crawlSeq);
    }

    class CrawlerClient extends WebViewClient {
        private CrawlerClient() {
        }

        @Override
        public void onPageFinished(final WebView webView, String str) {
            super.onPageFinished(webView, str);
            Log.d(HomeFragment.TAG, "后台页面完成: " + str);
            String str2 = "[page] " + str;
            HomeFragment.this.notifyGradeLog(str2);
            HomeFragment.this.notifyTimetableLog(str2);
            if (HomeFragment.this.isCrawling) {
                if (HomeFragment.this.isLoginPage(str)) {
                    if (HomeFragment.this.hasInjectedLogin) {
                        Log.d(str2, "已在登录页处理过，跳过");
                        return;
                    }
                    Log.d(str2, "后台检测到登录页，启动自动登录...");
                    HomeFragment.this.notifyFrontProgress(30, "正在登录统一认证…");
                    HomeFragment.this.hasInjectedLogin = true;
                    webView.postDelayed(new Runnable() {
                        @Override
                        public final void run() {
                            HomeFragment.this.notifyFrontProgress(45, "正在填写账号密码…");
                            HomeFragment.this.injectAutoLogin(webView);
                        }
                    }, 1000L);
                    return;
                }
                if (!str.contains("xsMainV_new.htmlx")) {
                    if (str.contains("cjcx_frm")) {
                        webView.postDelayed(new InjectGradeRunnable(HomeFragment.this), 2000L);
                        return;
                    } else if (!str.contains("xsMainV.htmlx")) {
                        return;
                    }
                }
                Log.d(str2, "后台到达课表页，准备提取...");
                HomeFragment.this.notifyFrontProgress(85, HomeFragment.this.isGradeMode() ? "登录成功，正在读取成绩…" : "登录成功，正在读取课表…");
                webView.postDelayed(new Runnable() {
                    @Override
                    public final void run() {
                        if (HomeFragment.this.isGradeMode()) {
                            HomeFragment.this.startGradeCrawl();
                        } else {
                            // 已进入教务主页，登录阶段结束：取消 20 秒登录超时，
                            // 后面交给解析超时兜底，避免正常解析被误报成「登录超时」
                            HomeFragment.this.cancelLoginTimeout();
                            HomeFragment.this.injectParser(webView);
                        }
                    }
                }, 1500L);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, String str) {
            Log.d(HomeFragment.TAG, "shouldOverrideUrlLoading: " + str);
            return false;
        }
    }

    public boolean isLoginPage(String str) {
        if (str == null) {
            return false;
        }
        // 统一认证的地址可能是 /login、/linkid/xxx，也可能只是带 service 参数的根路径。
        // 只要还停在 sso 域名上就按登录页处理：认证成功会跳回 jwxt.wuyiu.edu.cn。
        // 之前要求 URL 必须含 /linkid/ 或 /login，某些跳转会漏判 → 不注入 → 超时后误报失败。
        return str.contains("sso.wuyiu.edu.cn");
    }

    // 清除 SSO 登录页上一次残留的错误提示，并打上 stale 标记：
    // 这样超时读页面文本时不会把上一轮「密码错误」当成这一轮的判定依据
    public void clearSsoErrorNotice(final WebView webView) {
        String js = "(function(){try{var sels=['.ant-message-notice','.ant-alert-message','.ant-alert-error','.ant-form-item-explain-error','.ant-message','.ant-form-item-explain'];"
                + "for(var i=0;i<sels.length;i++){var ns=document.querySelectorAll(sels[i]);for(var j=0;j<ns.length;j++){var e=ns[j];var t=(e.innerText||'').trim();if(t&&(t.indexOf('密码')>=0||t.indexOf('验证码')>=0||t.indexOf('错误')>=0||t.indexOf('失败')>=0)){e.setAttribute('data-mimi-stale','1');e.style.display='none';}}}"
                + "}catch(_){}})();";
        webView.evaluateJavascript(js, null);
    }

    // 网络证据：捕获登录请求的提交内容与教务返回体，写进 App 内置日志（可一键复制发给开发者）
    // 只关心登录相关 URL，密码字段一律隐藏，避免日志外泄
    private static final String LOGIN_HOOK_JS =
            "(function(){try{if(window.__mimiLoginHook)return;window.__mimiLoginHook=1;"
            + "var log=function(m){try{AndroidBack.onLoginSignal(m);}catch(e){}};"
            + "var mask=function(s){var o=String(s||'');"
            + "o=o.replace(/(password|pwd|passwd|pass|mm)=([^&]*)/gi,'$1=<已隐藏>');"
            + "o=o.replace(/password([\"']?\\s*:\\s*)([\"'])([^\"']*)\\2/gi,'password$1$2<已隐藏>$2');"
            + "return o.substring(0,400);};"
            + "var cap=function(u,b,r){try{var us=String(u||'');if(!/login|auth|sso|linkid|doLogin/i.test(us))return;"
            + "var rs=String(r||'').replace(/\\s+/g,' ');"
            + "log('[网络] '+us+' | 提交 '+mask(b)+' | 返回 '+rs.substring(0,200));}catch(e){}};"
            + "try{var oo=XMLHttpRequest.prototype.open;var os=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,u){this.__mUrl=u;return oo.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(b){var x=this;x.addEventListener('load',function(){"
            + "var t='';try{if(!x.responseType||x.responseType==='text')t=x.responseText;}catch(e){}cap(x.__mUrl,b,t);});"
            + "return os.apply(this,arguments);};}catch(e){}"
            + "try{if(window.fetch){var of=window.fetch;window.fetch=function(i,init){"
            + "var u=(typeof i==='string')?i:(i&&i.url)||'';var b=(init&&init.body)?String(init.body):'';"
            + "return of.apply(this,arguments).then(function(r){try{r.clone().text().then(function(t){cap(u,b,t);});}catch(e){}return r;});};}}catch(e){}"
            + "}catch(e){}})();";

    public void injectAutoLogin(WebView webView) {
        String str = this.pendingUser;
        if (str == null || this.pendingPass == null) {
            return;
        }
        // 用 Gson 生成合法的 JS 字符串字面量：账号或密码里含单引号/反斜杠/换行时，
        // 手工拼接会把整段注入脚本写坏（脚本静默失败 → 超时 → 误报密码错误）
        String jsUser = this.gson.toJson(str);
        String jsPass = this.gson.toJson(this.pendingPass);
        // 先装网络钩子，捕获登录请求提交了什么、教务回了什么
        webView.evaluateJavascript(LOGIN_HOOK_JS, null);
        // 上一次失败留下的错误提示先隐藏并标记，避免它干扰本次判定
        clearSsoErrorNotice(webView);
        webView.evaluateJavascript("(function() {    var username = " + jsUser + ";    var password = " + jsPass + ";    var maxWait = 12000;    var startTime = Date.now();    var log = function(msg) { AndroidBack.onLog(msg); };    var error = function(msg) { AndroidBack.onError(msg); };    var ensureUsernameTab = function() {        if (document.getElementById('nameInput')) return true;        var tabs = document.querySelectorAll('.ant-tabs-tab, [role=\"tab\"]');        for (var i = 0; i < tabs.length; i++) {            var tx = tabs[i].textContent;            if (tx.indexOf('用户名密码') >= 0 || tx.indexOf('账号登录') >= 0) {                tabs[i].click();                log('已切换到用户名密码登录方式');                return false;            }        }        return false;    };    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;    var fillInput = function(el, val) {        nativeSetter.call(el, val);        el.dispatchEvent(new Event('input', { bubbles: true }));        el.dispatchEvent(new Event('change', { bubbles: true }));        if (el._valueTracker) el._valueTracker.setValue(val);    };    var tryClickLogin = function() {        var btn = document.getElementById('submitBtn');        if (!btn) {            error('未找到登录按钮 submitBtn');            return false;        }        log('找到登录按钮，当前 disabled: ' + btn.disabled);        btn.disabled = false;        btn.classList.remove('disabled');        log('已移除禁用属性');        btn.click();        log('已点击登录按钮');        return true;    };    var watchLoginError = function() {        var wstart = Date.now();        var kws = ['密码错误', '密码不正确', '账号不存在', '用户不存在', '验证码错误', '验证码不正确'];        var check = function() {            if (!document.getElementById('normalLoginForm') && !document.getElementById('nameInput')) return;            var msg = '';            var els = document.querySelectorAll('.ant-message-notice, .ant-alert-message, .ant-alert-error, .ant-form-item-explain-error');            for (var i = 0; i < els.length; i++) {                var t = (els[i].textContent || '').trim();                if (t) { msg = t; break; }            }            var bt = document.body ? (document.body.innerText || '') : '';            for (var k = 0; k < kws.length; k++) {                if (msg && msg.indexOf(kws[k]) >= 0) { error(msg); return; }                if (bt.indexOf(kws[k]) >= 0) { error(kws[k]); return; }            }            if (Date.now() - wstart < 15000) setTimeout(check, 500);        };        setTimeout(check, 800);    };    var loop = function() {        ensureUsernameTab();        var usernameInput = document.getElementById('nameInput');        var passwordInput = document.querySelector('input[type=\"password\"]');        if (usernameInput && passwordInput) {            var cap = document.querySelector('input#captcha, input[name=\"captcha\"], input[id*=\"captcha\"], input[id*=\"validateCode\"], input[name*=\"validateCode\"], input[id*=\"verifyCode\"], input[name*=\"verifyCode\"], input[placeholder*=\"验证码\"]');            if (cap) {                var rc = cap.getBoundingClientRect();                if (rc.width > 0 && rc.height > 0) {                    error('需要验证码');                    return;                }            }            log('找到输入框，开始填充');            fillInput(usernameInput, username);            fillInput(passwordInput, password);            log('已填充账号 ' + username + '，密码长度 ' + password.length);            setTimeout(function() {                if (!tryClickLogin()) {                    var form = document.getElementById('normalLoginForm');                    if (form) {                        log('尝试触发表单 submit 事件');                        var ev = new Event('submit', { bubbles: true, cancelable: true });                        form.dispatchEvent(ev);                        if (!ev.defaultPrevented) form.submit();                    }                }                watchLoginError();            }, 500);        } else {            var elapsed = Date.now() - startTime;            if (elapsed < maxWait) {                log('等待输入框出现... 已过 ' + elapsed + 'ms');                setTimeout(loop, 500);            } else {                error('登录页加载超时：未找到账号输入框');            }        }    };    loop();})();", null);
        // 2.5 秒后读一次页面上的实际值：确认提交的确实是刚填进去的账密，
        // 排查"密码改对了仍提示密码错误"时，可直接从内置日志看到账号/密码长度/当前地址
        this.mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                WebView wv = HomeFragment.this.crawlerView;
                if (wv == null || !HomeFragment.this.isCrawling) {
                    return;
                }
                wv.evaluateJavascript("(function(){try{var u=document.getElementById('nameInput');"
                        + "var p=document.querySelector('input[type=\"password\"]');"
                        + "AndroidBack.onLoginSignal('提交值自检：账号='+(u?u.value:'无')+'，密码长度='+(p?p.value.length:-1)+'，当前地址='+String(location.href).substring(0,90));"
                        + "}catch(e){}})()", null);
            }
        }, 2500L);
    }

    private void injectParser(WebView webView) {
        final int seq = this.crawlSeq;
        this.mainHandler.postDelayed(new Runnable() {
            @Override
            public final void run() {
                // 旧尝试的解析超时不要作用到新一次登录上
                if (seq == crawlSeq) {
                    onParseTimeout();
                }
            }
        }, 10000L);
        webView.evaluateJavascript("(function(){try{function dwk(doc){try{var re=/第\\s*(\\d{1,2})\\s*周(?!\\s*[-—~至])/;var i,m,t;var ss=doc.querySelectorAll('select');for(i=0;i<ss.length;i++){var so=ss[i].options[ss[i].selectedIndex];t=so?(so.text||''):'';m=t.match(re);if(m)return{n:parseInt(m[1],10),src:'select'};}var els=doc.querySelectorAll('button,span,div,a,li,td,th');for(i=0;i<els.length;i++){try{var cl=String(els[i].className||'');if(!/(cur|active|select|value|now|text|title)/i.test(cl))continue;t=(els[i].innerText||'').replace(/\\s+/g,'');if(t.length<2||t.length>8)continue;m=t.match(/^第(\\d{1,2})周$/);if(m)return{n:parseInt(m[1],10),src:'curcls'};}catch(_){}}for(i=0;i<els.length;i++){try{t=(els[i].innerText||'').replace(/\\s+/g,'');if(t.length<2||t.length>8)continue;m=t.match(/^第(\\d{1,2})周$/);if(!m)continue;var p=els[i].parentElement;var bad=0;for(var g=0;g<2&&p;g++){var pt=p.innerText||'';var re2=/第\\s*\\d{1,2}\\s*周/g;var c=0;while(re2.exec(pt)!==null)c++;if(c>=3){bad=1;break;}p=p.parentElement;}if(bad)continue;return{n:parseInt(m[1],10),src:'leaf'};}catch(_){}}var bt=(doc.body&&doc.body.innerText)||'';m=re.exec(bt);if(m)return{n:parseInt(m[1],10),src:'body'};}catch(_){}return null;}var n=0;var f=function(){var r=null;try{var docs=[document];var fr=document.getElementById(\"person\");if(!fr){var fl=document.querySelectorAll(\"iframe,frame\");if(fl&&fl.length){fr=fl[0];}}try{if(fr&&fr.contentDocument&&fr.contentDocument.body){docs.push(fr.contentDocument);}}catch(_){}for(var i=0;i<docs.length&&r===null;i++){r=dwk(docs[i]);}}catch(_){}if(r!==null){try{AndroidBack.onTimetableLog&&AndroidBack.onTimetableLog('[JS] 首页周次检测: 第'+r.n+'周 ('+r.src+')');}catch(_){}try{AndroidBack.onWeekNo(String(r.n));}catch(_){}}else if(++n<10){setTimeout(f,600);}};f();}catch(_){}})();", null);
        String parserJs = readAsset("www/timetable_parser.js");
        if (parserJs.length() == 0) {
            notifyFrontError("解析脚本读取失败");
            return;
        }
        webView.evaluateJavascript(parserJs, null);
    }

    /** 从 assets 读取静态脚本：避免把大段 JS 塞进 Java 字符串，便于维护与排查 */
    private String readAsset(String path) {
        java.io.InputStream in = null;
        try {
            in = getContext().getAssets().open(path);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读取静态脚本失败: " + path + " " + e.getMessage());
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    void onParseTimeout() {
        if (this.isCrawling) {
            Log.e(TAG, "解析超时");
            int i = this.retryCount;
            if (i < 1) {
                this.retryCount = i + 1;
                Log.w(TAG, "解析超时，第 " + this.retryCount + " 次重试...");
                this.isCrawling = false;
                this.hasInjectedLogin = false;
                scheduleRetry(2000L);
                return;
            }
            notifyFrontErrorOrStatus("解析超时，请重试");
            dismissLoginProgress();
            this.isCrawling = false;
            this.hasInjectedLogin = false;
            this.attemptQuiet = false;
        }
    }

    public class BackBridge {
        @JavascriptInterface
        public final void onGradeLog(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new GradeLogRunnable(homeFragment, str));
        }

        @JavascriptInterface
        public final void onGradeTerms(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new GradeTermsRunnable(homeFragment, str));
        }

        @JavascriptInterface
        public void onGrades(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new GradeNotifyRunnable(homeFragment, str));
            if (homeFragment.timeoutRunnable != null) {
                homeFragment.mainHandler.removeCallbacks(homeFragment.timeoutRunnable);
            }
            HomeFragment.this.isCrawling = false;
            HomeFragment.this.hasInjectedLogin = false;
            new Thread(new StatsReporter(HomeFragment.this.requireContext(), "grade")).start();
        }

        @JavascriptInterface
        public final void onTimetableLog(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new TimetableLogRunnable(homeFragment, str));
        }

        @JavascriptInterface
        public void onWeek1Courses(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new ForwardMsgRunnable(homeFragment, "week1courses", str));
        }

        @JavascriptInterface
        public void onWeekNo(String str) {
            HomeFragment homeFragment = HomeFragment.this;
            homeFragment.mainHandler.post(new ForwardMsgRunnable(homeFragment, "weekno", str));
        }

        public BackBridge() {
        }

        @JavascriptInterface
        public void onCourses(final String str) {
            boolean zTmp = false;
            Log.d(HomeFragment.TAG, "后台提取成功，JSON长度: " + str.length());
            try {
                List list = (List) HomeFragment.this.gson.fromJson(str, List.class);
                zTmp = (list == null || list.isEmpty()) ? false : true;
            } catch (Exception unused) {
            }
            final boolean z = zTmp;
            if (z || HomeFragment.this.retryCount >= 1) {
                if (HomeFragment.this.timeoutRunnable != null) {
                    HomeFragment.this.mainHandler.removeCallbacks(HomeFragment.this.timeoutRunnable);
                }
                HomeFragment.this.isCrawling = false;
                HomeFragment.this.hasInjectedLogin = false;
                HomeFragment.this.attemptQuiet = false;
                HomeFragment.this.bgHappened = false; // 本次抓取已结束，清除"曾切后台"标记
                HomeFragment.this.saveCookies();
                if (HomeFragment.this.pendingUser != null && HomeFragment.this.pendingPass != null) {
                    HomeFragment.this.prefs.edit().putString(HomeFragment.KEY_USERNAME, HomeFragment.this.pendingUser).putString(HomeFragment.KEY_PASSWORD, HomeFragment.this.pendingPass).apply();
                    Log.d(HomeFragment.TAG, "已保存账号密码");
                }
                new Thread(new StatsReporter(HomeFragment.this.requireContext(), "timetable")).start();
                // 保存课表数据并刷新桌面小组件
                HomeFragment.this.saveWidgetData(str);
                HomeFragment.this.mainHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        deliverCourses(str, z);
                    }
                });
                return;
            }
            HomeFragment.this.retryCount++;
            Log.w(HomeFragment.TAG, "解析到空课表，第 " + HomeFragment.this.retryCount + " 次重试...");
            HomeFragment.this.notifyFrontErrorOrStatus("获取课表为空，正在重试...");
            HomeFragment.this.isCrawling = false;
            HomeFragment.this.hasInjectedLogin = false;
            HomeFragment.this.scheduleRetry(2000L);
        }

        void deliverCourses(String str, boolean z) {
            HomeFragment.this.dismissLoginProgress();
            if (z) {
                HomeFragment.this.notifyFrontProgress(100, "更新完成");
            }
            String strReplace = str.replace("'", "\\'");
            HomeFragment.this.webView.evaluateJavascript("window.receiveFromAndroid('courses', '" + strReplace + "');", null);
            HomeFragment homeFragment = HomeFragment.this;
            if (z) {
                homeFragment.webView.evaluateJavascript("window.receiveFromAndroid('status', '登录成功');", null);
            } else {
                homeFragment.webView.evaluateJavascript("window.receiveFromAndroid('status', '获取课表失败，请重试');", null);
            }
        }

        @JavascriptInterface
        public void onError(final String str) {
            Log.e(HomeFragment.TAG, "后台错误: " + str);
            // 本次尝试已经结束（可能已提示过一次）：忽略同一页面上重复上报的错误，避免连环弹窗
            if (!HomeFragment.this.isCrawling) {
                return;
            }
            // 页面出现了验证码：自动填写无法完成，直接说明原因，别当成"密码错误"
            if (str.contains("需要验证码")) {
                HomeFragment.this.cancelLoginTimeout();
                HomeFragment.this.isCrawling = false;
                HomeFragment.this.hasInjectedLogin = false;
                HomeFragment.this.bgHappened = false;
                final boolean quietCap = HomeFragment.this.attemptQuiet;
                HomeFragment.this.attemptQuiet = false;
                HomeFragment.this.mainHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        HomeFragment.this.dismissLoginProgress();
                        if (quietCap) {
                            HomeFragment.this.notifyFrontStatus("自动更新失败：教务要求验证码");
                        } else {
                            HomeFragment.this.showFrontError("教务要求验证码，请稍后再试");
                            try {
                                Context context = HomeFragment.this.getContext();
                                if (context != null) {
                                    MimiDialogBuilder builder = new MimiDialogBuilder(context);
                                    builder.setTitle("需要验证码");
                                    builder.setMessage("教务系统要求输入验证码。请稍后重试，或先在手机浏览器打开教务系统登录一次再回来。");
                                    builder.setPositiveButton("好的", (DialogInterface.OnClickListener) null);
                                    builder.show();
                                }
                            } catch (Throwable th) {
                            }
                        }
                    }
                });
                return;
            }
            HomeFragment homeFragment = HomeFragment.this;
            // 教务返回的账号/密码错误 → 直接显示，不触发重试
            if (HomeFragment.isCredentialError(str)) {
                HomeFragment.this.cancelLoginTimeout();
                HomeFragment.this.isCrawling = false;
                HomeFragment.this.hasInjectedLogin = false;
                HomeFragment.this.bgHappened = false;
                final boolean quiet = HomeFragment.this.attemptQuiet;
                HomeFragment.this.attemptQuiet = false;
                HomeFragment.this.mainHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        if (quiet) {
                            HomeFragment.this.dismissLoginProgress();
                            HomeFragment.this.notifyFrontStatus("自动更新失败：" + str);
                        } else {
                            // 账密明确错误：清空账号/密码/课表缓存并回到登录页，让用户重新输入
                            HomeFragment.this.dismissLoginProgress();
                            HomeFragment.this.clearAuthStateAndCache("账号或密码输入错误，请重新输入");
                            deliverError(str);
                        }
                    }
                });
                return;
            }
            // 非账密错误且未重试过 → 自动重试一次
            int i = HomeFragment.this.retryCount;
            if (i < 1) {
                homeFragment.retryCount++;
                Log.w(HomeFragment.TAG, "解析错误，第 " + HomeFragment.this.retryCount + " 次重试...");
                HomeFragment.this.notifyFrontErrorOrStatus("获取失败，正在重试...");
                HomeFragment.this.isCrawling = false;
                HomeFragment.this.hasInjectedLogin = false;
                HomeFragment.this.scheduleRetry(2000L);
                return;
            }
            // 重试后仍失败 → 显示错误
            HomeFragment.this.cancelLoginTimeout();
            HomeFragment.this.isCrawling = false;
            HomeFragment.this.hasInjectedLogin = false;
            HomeFragment.this.bgHappened = false;
            final boolean quiet = HomeFragment.this.attemptQuiet;
            HomeFragment.this.attemptQuiet = false;
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public final void run() {
                    if (quiet) {
                        HomeFragment.this.dismissLoginProgress();
                        HomeFragment.this.notifyFrontStatus("自动更新失败：" + str);
                    } else {
                        deliverError(str);
                    }
                }
            });
        }

        void deliverError(String str) {
            HomeFragment.this.dismissLoginProgress();
            String strReplace = str.replace("'", "\\'");
            HomeFragment.this.webView.evaluateJavascript("window.receiveFromAndroid('error', '" + strReplace + "');", null);
            HomeFragment.this.webView.evaluateJavascript("window.receiveFromAndroid('status', '登录失败: " + strReplace + "');", null);
        }

        @JavascriptInterface
        public void onLog(String str) {
            Log.d(HomeFragment.TAG, "后台JS: " + str);
        }

        @JavascriptInterface
        public void onLoginSignal(final String str) {
            Log.d(HomeFragment.TAG, "登录信号: " + str);
            // 只把关键的登录证据写进内置日志（可一键复制），其余仍留在 logcat
            if (str == null || (!str.startsWith("[网络]") && !str.startsWith("提交值自检"))) {
                return;
            }
            HomeFragment.this.mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    HomeFragment.this.notifyTimetableLog("[A] " + str);
                }
            });
        }
    }

    public void notifyFrontError(final String str) {
        this.mainHandler.post(new Runnable() {
            @Override
            public final void run() {
                showFrontError(str);
            }
        });
    }

    /** 登录进度：给前端发百分比，前端显示成「正在登录统一认证… 30%」 */
    void notifyFrontProgress(final int pct, final String text) {
        if (this.lastAttemptQuiet) {
            return;
        }
        final String safe = text == null ? "" : text.replace("\\", "\\\\").replace("'", "\\'");
        this.mainHandler.post(new Runnable() {
            @Override
            public final void run() {
                WebView webView = HomeFragment.this.webView;
                if (webView == null) {
                    return;
                }
                webView.evaluateJavascript("window.mimiLoginProgress&&window.mimiLoginProgress(" + pct + ",'" + safe + "');", null);
            }
        });
    }

    /** 静默登录失败时用：只回传一条状态消息写进日志，不碰登录页提示、不弹窗 */
    public void notifyFrontStatus(final String str) {
        this.mainHandler.post(new Runnable() {
            @Override
            public final void run() {
                WebView webView = HomeFragment.this.webView;
                if (webView == null) {
                    return;
                }
                String strReplace = str.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript("window.receiveFromAndroid('status', '" + strReplace + "');", null);
            }
        });
    }

    /** 按当前尝试类型选择提示通道：后台自动更新只写日志，手动登录才显示错误 */
    void notifyFrontErrorOrStatus(final String str) {
        if (this.attemptQuiet) {
            notifyFrontStatus(str);
        } else {
            notifyFrontError(str);
        }
    }

    void showFrontError(String str) {
        String strReplace = str.replace("'", "\\'");
        this.webView.evaluateJavascript("var tip=document.getElementById('tip');if(tip){tip.className='tip err';tip.textContent='" + strReplace + "';}var lb=document.getElementById('loginBtn');var btn=document.querySelector('.btn .txt');if(btn)btn.textContent=(lb&&lb.getAttribute('data-label'))?lb.getAttribute('data-label'):'登录教务系统';var spin=document.querySelector('.btn .loading');if(spin)spin.classList.add('hidden');if(lb)lb.disabled=false;var lp=document.getElementById('loginProgress');if(lp)lp.classList.add('hidden');", null);
    }

    // 把前端解析好的课表 JSON 转交给桌面小组件（原样保存全量数据，渲染时再按当天过滤）
    public void saveWidgetData(String coursesJson) {
        try {
            if (this.prefs == null || coursesJson == null || coursesJson.length() < 3) {
                return;
            }
            List list = (List) this.gson.fromJson(coursesJson, List.class);
            if (list == null || list.isEmpty()) {
                Log.w(TAG, "小组件：前端课表为空，跳过");
                return;
            }

            // 当前教学周：优先取前端通过 setWidgetWeek 回传的值，缺失时按学期起始日估算
            int weekNo = this.prefs.getInt("mimi_week_no", 0);
            if (weekNo <= 0) {
                long semStart = this.prefs.getLong("mimi_semester_start", 0L);
                if (semStart > 0) {
                    weekNo = (int) ((System.currentTimeMillis() - semStart) / 604800000L) + 1;
                }
            }
            if (weekNo <= 0) {
                weekNo = 1;
            }
            int totalWeek = Math.max(CourseWidgetService.DEFAULT_TOTAL_WEEK, weekNo);

            Context ctx = getContext();
            if (ctx == null) {
                return;
            }
            CourseWidgetService.saveCourses(ctx, coursesJson);
            CourseWidgetService.saveWeekNo(ctx, weekNo);
            CourseWidgetService.saveTotalWeek(ctx, totalWeek);
            CourseWidgetService.refreshAll(ctx);
            // 课表更新后重排未来 7 天的上课提醒与每日汇总提醒
            CourseReminderScheduler.reschedule(ctx);
            Log.d(TAG, "小组件已更新：共 " + list.size() + " 门课程，当前第 " + weekNo + "/" + totalWeek + " 周");
        } catch (Exception e) {
            Log.e(TAG, "保存小组件数据失败", e);
        }
    }

    public void saveCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        String cookie = cookieManager.getCookie("https://jwxt.wuyiu.edu.cn");
        String cookie2 = cookieManager.getCookie("https://sso.wuyiu.edu.cn");
        if (cookie == null && cookie2 == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (cookie == null) {
            cookie = HttpUrl.FRAGMENT_ENCODE_SET;
        }
        sb.append(cookie);
        sb.append(";;");
        if (cookie2 == null) {
            cookie2 = HttpUrl.FRAGMENT_ENCODE_SET;
        }
        sb.append(cookie2);
        this.prefs.edit().putString(KEY_COOKIES, sb.toString()).apply();
    }

    private void restoreCookies() {
        String str;
        String str2;
        // 没有保存的账号密码时不再恢复登录 Cookie：否则清空账号后依然会「免密」进入教务
        String savedUser = this.prefs.getString(KEY_USERNAME, null);
        String savedPass = this.prefs.getString(KEY_PASSWORD, null);
        if (savedUser == null || savedUser.isEmpty() || savedPass == null || savedPass.isEmpty()) {
            this.prefs.edit().remove(KEY_COOKIES).apply();
            return;
        }
        String string = this.prefs.getString(KEY_COOKIES, null);
        if (string == null || string.isEmpty()) {
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        String[] strArrSplit = string.split(";;");
        if (strArrSplit.length >= 1 && (str2 = strArrSplit[0]) != null && !str2.isEmpty()) {
            for (String str3 : strArrSplit[0].split(";")) {
                String strTrim = str3.trim();
                if (!strTrim.isEmpty()) {
                    cookieManager.setCookie("https://jwxt.wuyiu.edu.cn", strTrim);
                }
            }
        }
        if (strArrSplit.length >= 2 && (str = strArrSplit[1]) != null && !str.isEmpty()) {
            for (String str4 : strArrSplit[1].split(";")) {
                String strTrim2 = str4.trim();
                if (!strTrim2.isEmpty()) {
                    cookieManager.setCookie("https://sso.wuyiu.edu.cn", strTrim2);
                }
            }
        }
        cookieManager.flush();
    }

    public void clearAllData() {
        this.prefs.edit().remove(KEY_COOKIES).remove(KEY_USERNAME).remove(KEY_PASSWORD).apply();
        // 重新登录教务只清教务会话，不影响快递身份码的登录态
        clearJwLoginCookies();
        this.pendingUser = null;
        this.pendingPass = null;
        this.retryCount = 0;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 页面彻底销毁时释放缓存的视图与 WebView，避免内存泄漏
        this.cachedView = null;
        if (this.webView != null) {
            this.webView.destroy();
            this.webView = null;
        }
    }

    @Override
    public void onDestroyView() {
        dismissLoginProgress();
        super.onDestroyView();
        WebView webView = this.crawlerView;
        if (webView != null) {
            webView.destroy();
            this.isCrawling = false;
            this.hasInjectedLogin = false;
            this.retryCount = 0;
        }
        Runnable runnable = this.timeoutRunnable;
        if (runnable != null) {
            this.mainHandler.removeCallbacks(runnable);
        }
    }

    public static class StatsReporter implements Runnable {
        private final String action;
        private final Context ctx;

        StatsReporter(Context context, String str) {
            this.ctx = context.getApplicationContext();
            this.action = str;
        }

        @Override
        public void run() {
            try {
                SharedPreferences sharedPreferences = this.ctx.getSharedPreferences("WuyiKebiaoPrefs", 0);
                String string = sharedPreferences.getString("saved_username", null);
                if (string == null || string.isEmpty()) {
                    return;
                }
                HttpURLConnection httpURLConnection = (HttpURLConnection) new URL("https://aokiisei.xyz/mimi/stats.php").openConnection();
                httpURLConnection.setRequestMethod("POST");
                httpURLConnection.setDoOutput(true);
                httpURLConnection.setConnectTimeout(8000);
                httpURLConnection.setReadTimeout(8000);
                httpURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                byte[] bytes = ("uid=" + URLEncoder.encode(string, "UTF-8") + "&action=" + this.action).getBytes("UTF-8");
                OutputStream outputStream = httpURLConnection.getOutputStream();
                outputStream.write(bytes);
                outputStream.flush();
                outputStream.close();
                httpURLConnection.getResponseCode();
                httpURLConnection.disconnect();
            } catch (Throwable unused) {
            }
        }
    }
}
