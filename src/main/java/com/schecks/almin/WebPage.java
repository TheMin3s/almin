package com.schecks.almin;

/**
 * The web panel's single page: markup, styles and script in one string.
 *
 * <p>It lives apart from {@link WebUi} only because of its size — the panel is
 * one self-contained document with no external assets, so there is nothing to
 * host, cache-bust or fetch, and a strict-ish set of response headers can be
 * applied to it uniformly.
 */
final class WebPage {
    private WebPage() {}

    /**
     * The panel page.
     *
     * <p>The tiles read the raw {@code metrics} object; the section cards read
     * the pre-formatted {@code rows}. Status colours (TPS health, memory
     * pressure) always ship with a word next to them — "Healthy", "Strained",
     * "Critical" — so state is never carried by colour alone.
     */
    static final String HTML = """
        <!doctype html><meta charset="utf-8"><title>Almin</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          :root{
            --bg:#101216; --card:#181b21; --card2:#1e222a; --line:#2b3039;
            --ink:#e8eaed; --dim:#98a1ae; --mute:#6b7480; --brand:#ffab33;
            --good:#0ca30c; --warn:#fab219; --crit:#d03b3b; --track:#272c35;
          }
          *{box-sizing:border-box}
          body{background:var(--bg);color:var(--ink);margin:0;
               font:14px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;
               -webkit-font-smoothing:antialiased}
          .num{font-variant-numeric:tabular-nums}

          header{display:flex;align-items:center;gap:14px;padding:13px 22px;
                 background:linear-gradient(180deg,#181b21,#141720);
                 border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
          .brand{font-size:15px;font-weight:650;letter-spacing:.4px;color:var(--brand)}
          .pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;
                font-size:12px;font-weight:600;border:1px solid var(--line);background:var(--card2);color:var(--dim)}
          .dot{width:7px;height:7px;border-radius:50%;background:var(--mute);flex:none}
          .pill.up .dot{background:var(--good);box-shadow:0 0 0 3px rgba(12,163,12,.18)}
          .pill.down .dot{background:var(--crit);box-shadow:0 0 0 3px rgba(208,59,59,.18)}
          .pill.up{color:#9ee39e}.pill.down{color:#f0a3a3}
          .pill span:last-child{white-space:nowrap}
          .spacer{margin-left:auto}
          .age{color:var(--mute);font-size:12px;white-space:nowrap}
          button{font:inherit;cursor:pointer}
          .btn{background:var(--card2);border:1px solid var(--line);color:var(--ink);
               padding:6px 13px;border-radius:7px;white-space:nowrap;
               transition:border-color .15s,background .15s}
          .btn:hover{border-color:var(--brand);background:#232833}
          .btn.danger:hover{border-color:var(--crit);color:#ffb3b3}
          .btn.go:hover{border-color:var(--good);color:#a8e6a8}
          .btn[disabled]{opacity:.4;cursor:not-allowed}
          .btn[disabled]:hover{border-color:var(--line);background:var(--card2);color:var(--ink)}

          nav{display:flex;gap:2px;padding:12px 22px 0;flex-wrap:wrap;border-bottom:1px solid var(--line)}
          nav button{background:none;border:0;border-bottom:2px solid transparent;color:var(--dim);
                     padding:8px 15px;border-radius:6px 6px 0 0;font-weight:500}
          nav button:hover{color:var(--ink)}
          nav button.on{color:var(--brand);border-bottom-color:var(--brand)}
          main{padding:20px 22px 34px;max-width:1500px;margin:0 auto}
          .panel{display:none}.panel.on{display:block}

          /* ---- KPI tiles ---- */
          .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:13px}
          .tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px 13px;
                position:relative;overflow:hidden}
          .tile::before{content:"";position:absolute;inset:0 0 auto 0;height:2px;background:var(--track)}
          .tile.good::before{background:var(--good)}
          .tile.warn::before{background:var(--warn)}
          .tile.crit::before{background:var(--crit)}
          .tile .cap{font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--mute);font-weight:600}
          .tile .big{font-size:29px;font-weight:640;line-height:1.15;margin-top:5px;letter-spacing:-.5px}
          .tile .sub{font-size:12px;color:var(--dim);margin-top:2px}
          .state{font-size:11px;font-weight:700;letter-spacing:.4px;text-transform:uppercase}
          .state.good{color:#57c957}.state.warn{color:var(--warn)}.state.crit{color:#e97070}
          .meter{height:6px;border-radius:99px;background:var(--track);margin-top:9px;overflow:hidden}
          .meter i{display:block;height:100%;border-radius:99px;background:var(--brand);
                   transition:width .45s cubic-bezier(.4,0,.2,1)}
          .meter i.good{background:var(--good)}.meter i.warn{background:var(--warn)}.meter i.crit{background:var(--crit)}
          .spark{margin-top:8px;display:block;width:100%;height:32px;overflow:visible}
          .sparkwrap{position:relative}
          .sparktip{position:absolute;pointer-events:none;background:#0b0d11;border:1px solid var(--line);
                    border-radius:6px;padding:2px 7px;font-size:11px;color:var(--ink);white-space:nowrap;
                    transform:translate(-50%,-120%);opacity:0;transition:opacity .12s}

          /* ---- section cards ---- */
          .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:13px;margin-top:16px}
          section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
          h2{margin:0 0 11px;font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--brand);font-weight:700}
          .row{display:flex;gap:12px;padding:5px 0;border-bottom:1px solid rgba(255,255,255,.045)}
          .row:last-child{border-bottom:0}
          .k{color:var(--dim);white-space:nowrap}
          .v{margin-left:auto;text-align:right;font-variant-numeric:tabular-nums}
          .note{color:var(--mute);font-style:italic;padding:5px 0;font-size:13px}

          pre{background:#0b0d11;border:1px solid var(--line);border-radius:12px;margin-top:14px;
              padding:13px 15px;max-height:62vh;overflow:auto;white-space:pre-wrap;word-break:break-word;
              font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;color:#c9d1d9}
          .warnline{color:var(--warn)}.errline{color:#ff7a6b}

          input,textarea{background:#0b0d11;border:1px solid var(--line);color:var(--ink);border-radius:8px;
                         padding:9px 11px;font:inherit;width:100%;outline:none}
          input:focus,textarea:focus{border-color:var(--brand)}
          textarea{font:12px/1.5 ui-monospace,Menlo,monospace;min-height:50vh;resize:vertical}
          .term{display:flex;gap:8px;margin-top:12px}
          .term input{font:12px/1.5 ui-monospace,Menlo,monospace}
          .files{display:grid;grid-template-columns:minmax(250px,1fr) 2fr;gap:14px;margin-top:14px}
          .flist{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:8px;
                 max-height:64vh;overflow:auto}
          .fentry{padding:6px 9px;border-radius:7px;cursor:pointer;display:flex;gap:8px;align-items:center}
          .fentry:hover{background:var(--card2)}
          .fentry .sz{margin-left:auto;color:var(--mute);font-size:11px}
          .dir{color:var(--brand)}
          .editor{display:flex;flex-direction:column;gap:9px}
          .editor .bar{display:flex;gap:8px;align-items:center}
          .editor .bar input{flex:1}
          .msg{min-height:18px;font-size:12px;color:var(--dim)}
          .msg.err{color:#e97070}.msg.ok{color:#57c957}
          .login{max-width:380px;margin:52px auto;background:var(--card);border:1px solid var(--line);
                 border-radius:12px;padding:26px}
          .login h2{margin-top:0}
          .login .btn{width:100%;margin-top:11px;padding:10px}
          .muted{color:var(--dim);font-size:12px}
          code{background:#0b0d11;padding:1px 5px;border-radius:4px;font-size:12px}
          .banner{display:flex;align-items:center;gap:12px;background:var(--card);border:1px solid var(--line);
                  border-left:3px solid var(--crit);border-radius:10px;padding:13px 16px;margin-bottom:16px}
          .btn.on{border-color:var(--good);color:#a8e6a8}
          .act{max-height:64vh;overflow:auto;background:var(--card);border:1px solid var(--line);
               border-radius:12px;padding:6px 4px}
          .arow{display:grid;grid-template-columns:52px 130px 110px 1fr auto;gap:10px;align-items:baseline;
                padding:5px 10px;border-bottom:1px solid rgba(255,255,255,.045);font-size:13px}
          .arow:last-child{border-bottom:0}
          .arow .ago{color:var(--mute);font-variant-numeric:tabular-nums}
          .arow .who{color:var(--ink);font-weight:600;overflow:hidden;text-overflow:ellipsis}
          .arow .what{font-weight:600}
          .arow .det{color:var(--dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
          .arow .at{color:var(--mute);font-size:12px;white-space:nowrap}
          @media(max-width:760px){.arow{grid-template-columns:46px 1fr;row-gap:2px}
                                  .arow .det,.arow .at{grid-column:1/-1}}
          .cfgrow{display:flex;gap:14px;align-items:center;padding:9px 0;
                  border-bottom:1px solid rgba(255,255,255,.045)}
          .cfgrow:last-child{border-bottom:0}
          .cfgrow>div:first-child{flex:1;min-width:0}
          .cfgname{font:12.5px ui-monospace,Menlo,monospace;color:var(--ink)}
          .cfgctl{display:flex;gap:8px;align-items:center;flex:none}
          .cfgctl input{width:auto}
          section+section{margin-top:13px}
          @media(max-width:620px){.cfgrow{flex-direction:column;align-items:stretch}
                                  .cfgctl{justify-content:flex-end}}
          /* The header holds a lot for its height; shed the least important
             parts before anything is allowed to wrap onto a second line. */
          @media(max-width:900px){ .age{display:none} }
          /* The status word always stays — a bare coloured dot would leave state
             carried by colour alone. Shed padding, never the label. */
          @media(max-width:620px){ header{padding:11px 14px;gap:9px} .pill{padding:4px 8px} }
          @media(max-width:760px){.files{grid-template-columns:1fr}main{padding:16px 14px 30px}}
        </style>
        <header>
          <span class="brand">ALMIN</span>
          <span class="pill" id="status"><span class="dot"></span><span id="statustext">…</span></span>
          <span class="spacer"></span>
          <span class="age" id="age">connecting…</span>
          <button class="btn" id="srvrestart" style="display:none">Restart</button>
          <button class="btn danger" id="srvstop" style="display:none">Stop server</button>
          <button class="btn go" id="srvstart" style="display:none">Start server</button>
          <button class="btn" id="logout" style="display:none">Log out</button>
        </header>
        <nav id="nav"></nav>
        <main id="main"></main>
        <script>
        const $ = id => document.getElementById(id);
        let authed=false, secure=false, encrypted=false, pwSet=false, publicMetrics=true;
        let serverRunning=true, canStart=false, supervisor=false;
        let tab='dash', last=null, stuck=true, tpsHistory=[];

        const esc = s => (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
        async function jget(u){ const r=await fetch(u,{credentials:'same-origin'});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }
        async function jpost(u,d){ const r=await fetch(u,{method:'POST',credentials:'same-origin',
            headers:{'Content-Type':'application/json'},body:JSON.stringify(d||{})});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }

        // ---- tiles ----
        function tpsState(tps,target){
          if(tps >= target-0.5) return ['good','Healthy'];
          if(tps >= target*0.75) return ['warn','Strained'];
          return ['crit','Critical'];
        }
        function memState(p){
          if(p>=90) return ['crit','Critical'];
          if(p>=75) return ['warn','High'];
          return ['good','Normal'];
        }
        function tile(cls,cap,big,sub,stateWord,meterPct,meterCls){
          const d=document.createElement('div');
          d.className='tile'+(cls?' '+cls:'');
          let h='<div class="cap">'+esc(cap)+'</div><div class="big num">'+esc(big)+'</div>';
          const bits=[];
          if(stateWord) bits.push('<span class="state '+cls+'">'+esc(stateWord)+'</span>');
          if(sub) bits.push('<span>'+esc(sub)+'</span>');
          if(bits.length) h+='<div class="sub">'+bits.join(' &middot; ')+'</div>';
          if(meterPct!=null) h+='<div class="meter"><i class="'+(meterCls||'')+'" style="width:'+
             Math.max(0,Math.min(100,meterPct))+'%"></i></div>';
          d.innerHTML=h;
          return d;
        }
        // One series, so no legend: the caption names it. 2px line, dot on the last sample.
        function sparkline(values,color){
          const wrap=document.createElement('div'); wrap.className='sparkwrap';
          if(values.length<2) return wrap;
          const W=200,H=32,pad=3;
          const min=Math.min(...values), max=Math.max(...values);
          const span=(max-min)||1;
          const pts=values.map((v,i)=>{
            const x=pad+(i/(values.length-1))*(W-pad*2);
            const y=H-pad-((v-min)/span)*(H-pad*2);
            return [x,y];
          });
          const d=pts.map((p,i)=>(i?'L':'M')+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' ');
          const area='M'+pts[0][0].toFixed(1)+' '+H+' '+pts.map(p=>'L'+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' ')
            +' L'+pts[pts.length-1][0].toFixed(1)+' '+H+' Z';
          const lastPt=pts[pts.length-1];
          wrap.innerHTML='<svg class="spark" viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="none" '+
            'role="img" aria-label="Recent TPS trend">'+
            '<defs><linearGradient id="sg" x1="0" x2="0" y1="0" y2="1">'+
            '<stop offset="0%" stop-color="'+color+'" stop-opacity=".28"/>'+
            '<stop offset="100%" stop-color="'+color+'" stop-opacity="0"/></linearGradient></defs>'+
            '<path d="'+area+'" fill="url(#sg)"/>'+
            '<path d="'+d+'" fill="none" stroke="'+color+'" stroke-width="2" '+
            'stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>'+
            '<circle cx="'+lastPt[0].toFixed(1)+'" cy="'+lastPt[1].toFixed(1)+'" r="2.5" fill="'+color+'"/>'+
            '</svg><div class="sparktip" id="sparktip"></div>';
          // Hover readout: nearest sample, value + how long ago.
          const svg=wrap.querySelector('svg'), tip=wrap.querySelector('.sparktip');
          svg.addEventListener('mousemove',e=>{
            const r=svg.getBoundingClientRect();
            const frac=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width));
            const i=Math.round(frac*(values.length-1));
            const agoS=(values.length-1-i)*3;
            tip.textContent=values[i].toFixed(2)+' TPS · '+(agoS===0?'now':agoS+'s ago');
            tip.style.left=(frac*r.width)+'px'; tip.style.top=(H-4)+'px'; tip.style.opacity='1';
          });
          svg.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          return wrap;
        }
        function buildTiles(m){
          const wrap=document.createElement('div'); wrap.className='tiles';
          if(!m) return wrap;
          const [tc,tw]=tpsState(m.tps,m.tpsTarget);
          const tpsTile=tile(tc,'Ticks per second',m.tps.toFixed(2),
            'target '+m.tpsTarget+' · '+m.mspt.toFixed(1)+' ms',tw,null,null);
          const col=tc==='good'?'#0ca30c':tc==='warn'?'#fab219':'#d03b3b';
          if(tpsHistory.length>1) tpsTile.appendChild(sparkline(tpsHistory,col));
          wrap.appendChild(tpsTile);

          const pPct=m.maxPlayers>0?(m.players/m.maxPlayers)*100:0;
          wrap.appendChild(tile('','Players online',m.players+' / '+m.maxPlayers,
            m.players===1?'1 player connected':m.players+' players connected',null,pPct,''));

          const [mc,mw]=memState(m.memPct);
          wrap.appendChild(tile(mc,'Memory',m.memPct+'%',m.memUsed+' of '+m.memMax,mw,m.memPct,mc));

          wrap.appendChild(tile('','Uptime',m.uptime,
            (m.chunks!=null? m.chunks.toLocaleString()+' chunks · '+m.entities.toLocaleString()+' entities':''),
            null,null,null));
          return wrap;
        }

        function rowsToGrid(rows){
          const grid=document.createElement('div'); grid.className='grid'; let sec=null;
          for(const r of rows){
            if(r.kind===0){ sec=document.createElement('section');
              const h=document.createElement('h2'); h.textContent=r.label; sec.appendChild(h);
              grid.appendChild(sec); }
            else if(sec){ const d=document.createElement('div');
              if(r.kind===2){ d.className='note'; d.textContent=r.label; }
              else { d.className='row';
                const k=document.createElement('span'); k.className='k'; k.textContent=r.label;
                const v=document.createElement('span'); v.className='v'; v.textContent=r.value;
                if(r.accent) v.style.color=r.accent; d.append(k,v); }
              sec.appendChild(d); } }
          return grid;
        }

        function setChrome(){
          const st=$('status'), txt=$('statustext');
          st.className='pill '+(serverRunning?'up':'down');
          txt.textContent=serverRunning?'Online':'Stopped';
          st.title=serverRunning?'Minecraft server is running':'Minecraft server is stopped';
          $('logout').style.display=authed?'':'none';
          $('srvstop').style.display=(authed&&serverRunning)?'':'none';
          $('srvrestart').style.display=(authed&&serverRunning)?'':'none';
          $('srvrestart').title=canStart?'Stop, then run the start command'
                                        :'Stops the server; your wrapper starts it again';
          $('srvstart').style.display=(authed&&!serverRunning)?'':'none';
          $('srvstart').disabled=!canStart;
          $('srvstart').title=canStart?'':'Set web-supervisor and web-start-command to enable';
          const nav=$('nav'); nav.innerHTML='';
          const tabs = authed ? [['dash','Overview'],['log','Console'],['term','Terminal'],
                                 ['activity','Activity'],['files','Files'],['players','Players'],
                                 ['mods','Mods'],['settings','Settings']]
                              : [['dash','Overview']];
          for(const [id,label] of tabs){
            const b=document.createElement('button'); b.textContent=label; b.className=(id===tab?'on':'');
            b.onclick=()=>{ tab=id; render(); }; nav.appendChild(b);
          }
        }

        function render(){
          setChrome();
          const m=$('main'); m.innerHTML='';
          if(!authed && tab!=='dash') tab='dash';
          if(tab==='dash') m.appendChild(dashPanel());
          else if(tab==='log') m.appendChild(consolePanel());
          else if(tab==='term') m.appendChild(termPanel());
          else if(tab==='files') m.appendChild(filesPanel());
          else if(tab==='mods') m.appendChild(modsPanel());
          else if(tab==='players') m.appendChild(playersPanel());
          else if(tab==='activity') m.appendChild(activityPanel());
          else if(tab==='settings') m.appendChild(settingsPanel());
        }

        function dashPanel(){
          const wrap=document.createElement('div');
          if(authed && !encrypted){
            const w=document.createElement('div'); w.className='banner';
            w.style.borderLeftColor='#fab219';
            w.innerHTML='<span class="state warn">HTTP</span><span class="muted">'+
              'This session is unencrypted. Anyone on the network path can read it. '+
              'Put a TLS proxy in front before using this over the internet.</span>';
            wrap.appendChild(w);
          }
          if(!serverRunning){
            const b=document.createElement('div'); b.className='banner';
            b.innerHTML='<span class="state crit">Stopped</span><span class="muted">'+
              'The Minecraft server is not running. '+
              (authed?(canStart?'Use <b>Start server</b> above.':'No start command is configured.')
                     :'Live metrics resume when it starts.')+'</span>';
            wrap.appendChild(b);
          }
          const metrics=document.createElement('div'); metrics.id='metrics';
          if(last) metrics.appendChild(paint(last));
          wrap.appendChild(metrics);
          if(!authed) wrap.appendChild(loginBox());
          return wrap;
        }
        function paint(d){
          const frag=document.createDocumentFragment();
          if(serverRunning && d.metrics) frag.appendChild(buildTiles(d.metrics));
          if(d.rows && d.rows.length) frag.appendChild(rowsToGrid(d.rows));
          return frag;
        }
        function updateMetrics(){
          const m=$('metrics'); if(!m) return;
          m.innerHTML=''; if(last) m.appendChild(paint(last));
        }
        function loginBox(){
          const box=document.createElement('div'); box.className='login';
          box.insertAdjacentHTML('beforeend','<h2>Admin login</h2>');
          if(!pwSet){ box.insertAdjacentHTML('beforeend',
            '<p class="muted">No admin password is set yet. In game, run '+
            '<code>/almin op web password &lt;password&gt;</code>.</p>'); return box; }
          if(!secure){ box.insertAdjacentHTML('beforeend',
            '<p class="muted">This server only accepts admin logins over HTTPS, or from the machine '+
            'it runs on. Either reach it through your TLS proxy, or run '+
            '<code>/almin config web-require-secure false</code> to allow plain HTTP.</p>');
            return box; }
          if(!encrypted){ box.insertAdjacentHTML('beforeend',
            '<p class="muted" style="color:#ffcc55">This connection is plain HTTP &mdash; your password '+
            'will cross the network unencrypted. Fine on a trusted LAN; put TLS in front before '+
            'exposing this to the internet.</p>'); }
          const pw=document.createElement('input'); pw.type='password'; pw.placeholder='Admin password';
          pw.autocomplete='current-password';
          const btn=document.createElement('button'); btn.className='btn'; btn.textContent='Log in';
          const msg=document.createElement('div'); msg.className='msg err';
          pw.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); doLogin(pw.value,msg); } };
          btn.onclick=()=>doLogin(pw.value,msg);
          box.append(pw,btn,msg);
          return box;
        }
        async function doLogin(pw,msg){
          msg.textContent='';
          const r=await jpost('/api/login',{password:pw});
          if(r.status===200){ authed=true; await refreshOnce(); tab='dash'; render(); }
          else if(r.status===429) msg.textContent='Too many attempts — locked out for '+(r.body.minutes||15)+' min.';
          else if(r.body&&r.body.remaining!=null) msg.textContent='Wrong password. '+r.body.remaining+' attempt(s) left.';
          else msg.textContent='Login failed.';
        }

        function consolePanel(){
          const wrap=document.createElement('div');
          const pre=document.createElement('pre'); pre.id='log'; wrap.appendChild(pre);
          pre.addEventListener('scroll',()=>{ stuck = pre.scrollTop+pre.clientHeight >= pre.scrollHeight-24; });
          loadConsole();
          return wrap;
        }
        async function loadConsole(){
          if(tab!=='log'&&tab!=='term') return;
          if(!serverRunning) return;
          const r=await jget('/api/console'); const pre=$('log'); if(!pre) return;
          pre.innerHTML=(r.body.lines||[]).map(l=>{
            const c=/\\/ERROR\\]| ERROR /.test(l)?'errline':/\\/WARN\\]| WARN /.test(l)?'warnline':'';
            return c?'<span class="'+c+'">'+esc(l)+'</span>':esc(l); }).join('\\n');
          if(stuck) pre.scrollTop=pre.scrollHeight;
        }

        function termPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">Runs a server command as the console (same as '+
            '<code>/almin op cmd</code>). Output appears below and in the Console tab.</p>';
          const bar=document.createElement('div'); bar.className='term';
          const inp=document.createElement('input'); inp.placeholder='say hello   (no leading slash needed)';
          const btn=document.createElement('button'); btn.className='btn'; btn.textContent='Run';
          const msg=document.createElement('div'); msg.className='msg';
          const run=async()=>{ const c=inp.value.trim(); if(!c) return;
            const r=await jpost('/api/exec',{command:c});
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent = r.status===200 ? 'ran: /'+r.body.ran : (r.body.error||'failed ('+r.status+')');
            inp.value=''; setTimeout(loadConsole,300); };
          inp.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); run(); } }; btn.onclick=run;
          const pre=document.createElement('pre'); pre.id='log';
          bar.append(inp,btn); wrap.append(bar,msg,pre);
          loadConsole();
          return wrap;
        }

        let curDir='';
        function filesPanel(){
          const wrap=document.createElement('div'); wrap.className='files';
          const listBox=document.createElement('div'); listBox.className='flist'; listBox.id='flist';
          const ed=document.createElement('div'); ed.className='editor';
          ed.innerHTML='<div class="bar"><input id="fpath" placeholder="path under a writable root, e.g. config/almin/config.json">'+
            '<button class="btn" id="fsave">Save</button><button class="btn" id="fdl">Download</button>'+
            '<button class="btn danger" id="fdel">Delete</button>'+
            '<button class="btn" id="fren">Rename</button></div>'+
            '<textarea id="fbody" placeholder="Select a file to edit, or type a path above and Save to create one."></textarea>'+
            '<div class="msg" id="fmsg"></div>'+
            '<section><h2>Put a file in this folder</h2>'+
            '<p class="muted" id="fupwhere"></p>'+
            '<input type="file" id="fup">'+
            '<button class="btn" id="fupgo" style="margin-top:8px">Upload</button>'+
            '<div class="msg" id="fupmsg"></div></section>'+
            '<section><h2>Download a link straight to the server</h2>'+
            '<div class="term"><input id="ffurl" placeholder="https://... link to a jar, pack or config">'+
            '<button class="btn" id="ffgo">Fetch</button></div>'+
            '<p class="muted" style="margin-top:6px">Saved into the folder shown on the left, '+
            'keeping the name from the link.</p>'+
            '<div class="msg" id="ffmsg"></div></section>';
          wrap.append(listBox,ed);
          setTimeout(()=>{ loadDir(curDir);
            $('fsave').onclick=saveFile; $('fdel').onclick=delFile; $('fren').onclick=renFile;
            $('fdl').onclick=dlFile; $('fupgo').onclick=upFile; $('ffgo').onclick=fetchUrl; },0);
          return wrap;
        }
        function dlFile(){
          const p=$('fpath').value.trim();
          if(!p){ const m=$('fmsg'); m.className='msg err'; m.textContent='Pick a file first.'; return; }
          // Same-origin GET, so the session cookie goes with it.
          location.href='/api/file/download?path='+encodeURIComponent(p);
        }
        async function upFile(){
          const inp=$('fup'), msg=$('fupmsg');
          if(!curDir){ msg.className='msg err';
            msg.textContent='Open a writable folder first \u2014 mods, config, resourcepacks or shared.'; return; }
          if(!inp.files || !inp.files.length){ msg.className='msg err'; msg.textContent='Choose a file first.'; return; }
          const f=inp.files[0];
          msg.className='msg'; msg.textContent='Uploading '+f.name+'\u2026';
          try{
            const r=await fetch('/api/file/upload?path='+encodeURIComponent(curDir+'/'+f.name),
              {method:'POST',credentials:'same-origin',
               headers:{'Content-Type':'application/octet-stream'},body:f});
            const b=await r.json().catch(()=>({}));
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent = r.status===200 ? ('uploaded '+b.path+' ('+b.bytes+' bytes)')
                                             : (b.error||'upload failed');
            if(r.status===200){ inp.value=''; loadDir(curDir); }
          }catch(e){ msg.className='msg err'; msg.textContent='upload failed'; }
        }
        async function fetchUrl(){
          const msg=$('ffmsg'), url=$('ffurl').value.trim();
          if(!curDir){ msg.className='msg err';
            msg.textContent='Open a writable folder first \u2014 mods, config, resourcepacks or shared.'; return; }
          if(!url){ msg.className='msg err'; msg.textContent='Paste a link first.'; return; }
          msg.className='msg'; msg.textContent='Fetching\u2026';
          const r=await jpost('/api/fetch',{url:url,dest:curDir+'/'});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.body.message||r.body.error||'fetch failed';
          if(r.status===200){ $('ffurl').value=''; loadDir(curDir); }
        }
        async function loadDir(path){
          curDir=path;
          const r=await jget('/api/files?path='+encodeURIComponent(path));
          const box=$('flist'); if(!box) return; box.innerHTML='';
          if(r.status!==200){ box.innerHTML='<div class="note">'+esc(r.body.error||'unavailable')+'</div>'; return; }
          const crumb=document.createElement('div'); crumb.className='fentry';
          crumb.innerHTML='<b>'+(path? esc('/'+path) : '/ (server root)')+'</b>'; box.appendChild(crumb);
          const where=$('fupwhere');
          if(where) where.textContent = path ? 'Lands in /'+path
            : 'The server root is not writable \u2014 open mods, config, resourcepacks or shared.';
          if(path){ const up=document.createElement('div'); up.className='fentry dir';
            up.textContent='↑ up'; up.onclick=()=>loadDir(path.split('/').slice(0,-1).join('/')); box.appendChild(up); }
          for(const e of (r.body.entries||[])){
            const row=document.createElement('div'); row.className='fentry'+(e.directory?' dir':'');
            const full = path ? path+'/'+e.name : e.name;
            row.innerHTML='<span>'+(e.directory?'📁':'📄')+' '+esc(e.name)+'</span>'+
              (e.directory?'':'<span class="sz">'+(e.size>=0?e.size+' B':'')+'</span>');
            row.onclick=()=> e.directory ? loadDir(full) : openFile(full);
            box.appendChild(row);
          }
        }
        async function openFile(path){
          const r=await jget('/api/file?path='+encodeURIComponent(path));
          const msg=$('fmsg');
          if(r.status!==200){ msg.className='msg err'; msg.textContent=r.body.error||'could not open'; return; }
          $('fpath').value=path; $('fbody').value=r.body.content; msg.className='msg'; msg.textContent='';
        }
        async function saveFile(){
          const r=await jpost('/api/file',{path:$('fpath').value,content:$('fbody').value});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'saved':(r.body.message||r.body.error||'save failed');
          if(r.body.ok) loadDir(curDir);
        }
        async function delFile(){
          const p=$('fpath').value; if(!p) return;
          if(!confirm('Delete '+p+'?')) return;
          const r=await jpost('/api/file/delete',{path:p});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'deleted':(r.body.message||r.body.error||'delete failed');
          if(r.body.ok){ $('fbody').value=''; $('fpath').value=''; loadDir(curDir); }
        }
        async function renFile(){
          const p=$('fpath').value; if(!p) return;
          const name=prompt('New name for '+p.split('/').pop()+':'); if(!name) return;
          const r=await jpost('/api/file/rename',{path:p,name:name});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'renamed':(r.body.message||r.body.error||'rename failed');
          if(r.body.ok) loadDir(curDir);
        }

        // ---- players and masks ----
        function fmtDur(ms){
          if(!ms || ms<0) return '0m';
          const m=Math.floor(ms/60000), h=Math.floor(m/60), d=Math.floor(h/24);
          if(d>0) return d+'d '+(h%24)+'h';
          if(h>0) return h+'h '+(m%60)+'m';
          return m+'m';
        }
        function fmtAgo(ts){
          if(!ts) return 'never';
          const s=Math.max(0,Math.round((Date.now()-ts)/1000));
          if(s<90) return 'just now';
          return fmtDur(s*1000)+' ago';
        }
        function playersPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">A mask changes how a player appears in chat and the tab list. '+
            'It is cosmetic — the account behind it is unchanged, and commands still use the real name.</p>'+
            '<div id="p-online"></div><div id="p-hist"></div><div class="msg" id="p-msg"></div>';
          setTimeout(loadPlayers,0);
          return wrap;
        }
        function playerRow(p,sub){
          const row=document.createElement('div'); row.className='row';
          row.style.alignItems='center'; row.style.gap='8px';
          const left=document.createElement('span'); left.className='k'; left.style.whiteSpace='normal';
          left.innerHTML='<b style="color:var(--ink)">'+esc(p.name)+'</b>'+
            (p.mask?' <span class="muted">shown as</span> <span style="color:var(--brand)">'+esc(p.mask)+'</span>':'')+
            '<br><span class="muted" style="font-size:12px">'+esc(sub)+'</span>';
          const set=document.createElement('button'); set.className='btn';
          set.textContent=p.mask?'Change mask':'Set mask'; set.style.marginLeft='auto';
          set.onclick=()=>{ const v=prompt('Display name for '+p.name+':', p.mask||'');
            if(v===null) return;
            const t=v.trim();
            sendMask(p.name, t, t===''); };
          row.append(left,set);
          if(p.mask){ const c=document.createElement('button'); c.className='btn danger'; c.textContent='Clear';
            c.onclick=()=>sendMask(p.name,'',true); row.appendChild(c); }
          return row;
        }
        async function loadPlayers(){
          const on=$('p-online'); if(!on) return;
          const r=await jget('/api/players');
          if(r.status!==200){ on.innerHTML='<section><div class="note">'+
            esc(r.body.error||'unavailable')+'</div></section>'; return; }
          const online=r.body.online||[];
          const hist=(r.body.history||[]).slice().sort((a,b)=>b.lastSeen-a.lastSeen);
          on.innerHTML='';
          const s1=document.createElement('section');
          s1.innerHTML='<h2>Online ('+online.length+' / '+(r.body.maxPlayers||0)+')</h2>';
          if(!online.length) s1.insertAdjacentHTML('beforeend','<div class="note">Nobody is connected.</div>');
          for(const p of online) s1.appendChild(playerRow(p, fmtDur(p.sessionMillis)+' this session'));
          on.appendChild(s1);
          const hs=$('p-hist'); hs.innerHTML='';
          const s2=document.createElement('section');
          s2.innerHTML='<h2>Seen before ('+hist.length+')</h2>';
          if(!hist.length) s2.insertAdjacentHTML('beforeend','<div class="note">No history recorded yet.</div>');
          for(const p of hist.slice(0,150))
            s2.appendChild(playerRow(p, p.joins+' join'+(p.joins===1?'':'s')+' · '+
              fmtDur(p.playtimeMillis)+' played · last seen '+fmtAgo(p.lastSeen)));
          hs.appendChild(s2);
        }
        async function sendMask(name,mask,clear){
          const r=await jpost('/api/mask',{name:name,mask:mask,clear:clear});
          const msg=$('p-msg'); msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?(r.body.message||'done'):(r.body.error||'failed');
          loadPlayers();
        }

        // ---- player activity ----
        const ACTION_COLOR = { chat:'#7fd1f0', command:'#ffab33', container:'#c792ea',
                               death:'#e05a5a', attack:'#ff8a65', join:'#57c957',
                               leave:'#8b9096' };
        function activityPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">What ordinary players have been doing. '+
            'Anyone who could read this — a trusted UUID, or any op — is never recorded, '+
            'and rows are deleted once they pass the retention window.</p>'+
            '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px">'+
            '<input id="a-filter" placeholder="filter by player, action, detail or place" '+
            'style="flex:1;min-width:200px">'+
            '<button class="btn" id="a-refresh">Refresh</button>'+
            '<button class="btn danger" id="a-clear">Clear log</button></div>'+
            '<div id="a-meta" class="muted" style="margin-bottom:8px"></div>'+
            '<div class="act" id="a-rows"><div class="note">loading…</div></div>'+
            '<div class="msg" id="a-msg"></div>';
          setTimeout(()=>{
            loadActivity();
            $('a-refresh').onclick=loadActivity;
            $('a-clear').onclick=clearActivity;
            // Filtering is client-side over the rows already fetched, so
            // typing here asks the server for nothing.
            $('a-filter').oninput=paintActivity;
          },0);
          return wrap;
        }
        let activityRows=[], activityMeta=null;
        async function loadActivity(){
          if(!$('a-rows')) return;
          const r=await jget('/api/activity');
          if(r.status!==200){ $('a-rows').innerHTML='<div class="note">'+
            esc(r.body.error||'unavailable')+'</div>'; return; }
          activityRows=r.body.rows||[]; activityMeta=r.body;
          paintActivity();
        }
        function paintActivity(){
          const box=$('a-rows'), meta=$('a-meta'); if(!box) return;
          const f=$('a-filter'), q=(f?f.value:'').trim().toLowerCase();
          const rows=q ? activityRows.filter(e=>
                (e.player+' '+e.action+' '+e.detail+' '+e.where).toLowerCase().includes(q))
              : activityRows;
          if(meta && activityMeta){
            meta.innerHTML = (activityMeta.enabled
                ? activityMeta.total+' row'+(activityMeta.total===1?'':'s')
                : '<span class="state warn">recording is off</span> · '+activityMeta.total+' kept')+
              ' · deleted after '+esc(humanMinutes(activityMeta.retentionMinutes))+
              (activityMeta.blocks?'':' · block edits excluded')+
              (q?' · '+rows.length+' shown':'');
          }
          if(!rows.length){ box.innerHTML='<div class="note">'+
            (q?'Nothing matches that filter.'
              :'Nothing recorded. Ops and trusted UUIDs are never recorded.')+'</div>'; return; }
          box.innerHTML='';
          for(const e of rows){
            const d=document.createElement('div'); d.className='arow';
            const col=ACTION_COLOR[e.action]||'#9aa3ae';
            d.innerHTML='<span class="ago">'+esc(fmtAgo(e.at).replace(' ago',''))+'</span>'+
              '<span class="who">'+esc(e.player)+'</span>'+
              '<span class="what" style="color:'+col+'">'+esc(e.action)+
                (e.count>1?' &times;'+e.count:'')+'</span>'+
              '<span class="det" title="'+esc(e.detail)+'">'+esc(e.detail)+'</span>'+
              '<span class="at">'+esc(e.where)+'</span>';
            box.appendChild(d);
          }
        }
        function humanMinutes(m){
          if(!m) return 'never';
          if(m%1440===0) return (m/1440)+(m/1440===1?' day':' days');
          if(m%60===0) return (m/60)+(m/60===1?' hour':' hours');
          return m+' minutes';
        }
        async function clearActivity(){
          if(!confirm('Delete the whole activity log?\\n\\nIt goes from memory and from disk, '+
                      'and cannot be recovered.')) return;
          const r=await jpost('/api/activity',{action:'clear'});
          const msg=$('a-msg'); msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?'Cleared.':(r.body.error||'failed');
          loadActivity();
        }

        // ---- settings ----
        function settingsPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML=
            '<section><h2>Admin password</h2>'+
            '<p class="muted">Changing it signs every other session out. You stay logged in here.</p>'+
            '<div class="term"><input id="s-pw" type="password" autocomplete="new-password" '+
            'placeholder="new password (8+ characters)">'+
            '<button class="btn" id="s-pwgo">Set</button></div>'+
            '<div class="msg" id="s-pwmsg"></div></section>'+
            '<section><h2>Version</h2><div id="s-update" class="muted">checking…</div>'+
            '<div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">'+
            '<button class="btn" id="s-check">Check again</button>'+
            '<button class="btn go" id="s-apply" disabled>Download &amp; install</button>'+
            '<button class="btn" id="s-clearlog">Clear Almin log</button></div>'+
            '<div class="msg" id="s-upmsg"></div></section>'+
            '<section><h2>Settings</h2>'+
            '<p class="muted">Written to <code>config/almin/config.json</code> as you change them, '+
            'and live immediately.</p>'+
            '<div id="s-keys"><div class="note">loading…</div></div>'+
            '<button class="btn" id="s-reload" style="margin-top:12px">Reload from disk</button>'+
            '<div class="msg" id="s-msg"></div></section>';
          setTimeout(()=>{
            loadConfig(); loadUpdate();
            $('s-pwgo').onclick=setPassword;
            $('s-pw').onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); setPassword(); } };
            $('s-check').onclick=()=>loadUpdate(true);
            $('s-apply').onclick=applyUpdate;
            $('s-clearlog').onclick=clearLog;
            $('s-reload').onclick=reloadConfig;
          },0);
          return wrap;
        }
        async function setPassword(){
          const msg=$('s-pwmsg'), v=$('s-pw').value;
          if(v.length<8){ msg.className='msg err'; msg.textContent='Use at least 8 characters.'; return; }
          const r=await jpost('/api/password',{password:v});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?'Password changed. Other sessions were signed out.'
                                        :(r.body.error||'failed');
          if(r.status===200){ $('s-pw').value=''; pwSet=true; }
        }
        async function loadUpdate(force){
          const box=$('s-update'), apply=$('s-apply'); if(!box) return;
          box.textContent='checking…'; if(apply) apply.disabled=true;
          // Without force the server answers from a five-minute cache, so
          // opening this tab doesn't call GitHub every time.
          const r=await jget('/api/update'+(force?'?force=1':''));
          if(r.status!==200){ box.textContent='unavailable'; return; }
          const b=r.body;
          const head='Running <b>v'+esc(b.current)+'</b> · <span class="muted">'+esc(b.repo||'')+'</span>';
          if(b.status==='current'){ box.innerHTML=head+' — up to date.'; }
          else if(b.status==='available'){
            box.innerHTML=head+' — <span class="state warn">v'+esc(b.latest)+' available</span>'+
              (b.hasJar?'':' <span class="muted">(no jar attached to that release)</span>');
            if(apply) apply.disabled=!b.hasJar;
          } else { box.innerHTML=head+' — check failed: '+esc(b.reason||'unknown'); }
        }
        async function applyUpdate(){
          const msg=$('s-upmsg');
          if(!confirm('Download and install the new version?\\n\\nIt takes effect when the server restarts.')) return;
          msg.className='msg'; msg.textContent='Downloading…'; $('s-apply').disabled=true;
          const r=await jpost('/api/update',{});
          msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.message||r.body.error||'failed';
          loadUpdate(true);
        }
        async function clearLog(){
          const msg=$('s-upmsg');
          const r=await jpost('/api/clearlog',{});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?'Almin log cleared.':(r.body.error||'failed');
        }
        async function reloadConfig(){
          const msg=$('s-msg');
          const r=await jpost('/api/config/reload',{});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?'Reloaded from disk.':(r.body.error||'failed');
          loadConfig();
        }
        async function loadConfig(){
          const box=$('s-keys'); if(!box) return;
          const r=await jget('/api/config');
          if(r.status!==200){ box.innerHTML='<div class="note">'+esc(r.body.error||'unavailable')+'</div>'; return; }
          box.innerHTML='';
          for(const k of (r.body.keys||[])) box.appendChild(cfgRow(k));
        }
        function cfgRow(k){
          const row=document.createElement('div'); row.className='cfgrow';
          const left=document.createElement('div');
          left.innerHTML='<div class="cfgname">'+esc(k.name)+'</div>'+
            '<div class="muted">'+esc(k.description)+'</div>';
          const ctl=document.createElement('div'); ctl.className='cfgctl';
          if(!k.editable){
            ctl.innerHTML='<span class="muted">'+esc(k.value||'—')+'</span>'+
              '<span class="state warn" title="Set this in game or at the server console">locked</span>';
          } else if(k.type==='BOOL'){
            const on=k.value==='true';
            const b=document.createElement('button');
            b.className='btn'+(on?' on':''); b.textContent=on?'on':'off';
            b.onclick=()=>setKey(k,on?'false':'true');
            ctl.appendChild(b);
          } else {
            const i=document.createElement('input'); i.value=k.value;
            if(k.type==='INT'){ i.type='number'; i.min=k.min; i.max=k.max; i.style.width='120px'; }
            else { i.style.width='260px'; i.style.maxWidth='42vw'; }
            const b=document.createElement('button'); b.className='btn'; b.textContent='Save';
            b.onclick=()=>setKey(k,i.value);
            i.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); setKey(k,i.value); } };
            ctl.append(i,b);
          }
          row.append(left,ctl);
          return row;
        }
        async function setKey(k,value){
          const msg=$('s-msg');
          if(k.name==='web-ui-enabled' && value==='false' &&
             !confirm('Turn the web panel off?\\n\\nThis page stops working straight away. '+
                      'You would turn it back on in game with /almin op web start.')) return;
          if(k.reloadsPanel &&
             !confirm('Changing '+k.name+' restarts the panel.\\n\\nThis page will drop for a moment, '+
                      'and may come back at a different address.')) return;
          const r=await jpost('/api/config',{name:k.name,value:String(value)});
          msg.className='msg '+(r.status===200?'ok':'err');
          if(r.status!==200){ msg.textContent=r.body.error||'failed'; return; }
          msg.textContent = r.body.panelStopping ? 'Panel stopping — this page is about to go dead.'
            : r.body.panelRestarting ? 'Panel restarting… reload this page in a few seconds.'
            : (k.name+' = '+r.body.value);
          if(!r.body.panelStopping && !r.body.panelRestarting) loadConfig();
        }
        function cfgToggle(name,label,on,after){
          const b=document.createElement('button');
          b.className='btn'+(on?' on':''); b.textContent=label+': '+(on?'on':'off');
          b.style.marginRight='8px';
          b.onclick=async()=>{ const r=await jpost('/api/config',{name:name,value:on?'false':'true'});
            if(r.status!==200) alert(r.body.error||'failed'); if(after) after(); };
          return b;
        }

        // ---- server control ----
        $('srvstop').onclick=async()=>{
          if(!confirm('Stop the Minecraft server?\\n\\nPlayers will be disconnected.')) return;
          $('srvstop').disabled=true;
          const r=await jpost('/api/server',{action:'stop'});
          if(r.status!==200){ alert(r.body.error||'Stop failed'); $('srvstop').disabled=false; }
        };
        $('srvrestart').onclick=async()=>{
          if(!confirm('Restart the Minecraft server?\\n\\nPlayers will be disconnected.')) return;
          $('srvrestart').disabled=true;
          const r=await jpost('/api/server',{action:'restart'});
          if(r.status!==200){ alert(r.body.error||'Restart failed'); $('srvrestart').disabled=false; }
          else $('age').textContent=r.body.message||'restarting…';
        };
        $('srvstart').onclick=async()=>{
          if(!canStart) return;
          if(!confirm('Start the Minecraft server?\\n\\nThe panel restarts with it and may be '+
                      'briefly unreachable.')) return;
          $('srvstart').disabled=true;
          const r=await jpost('/api/server',{action:'start'});
          if(r.status!==200){ alert(r.body.error||'Start failed'); $('srvstart').disabled=false; }
          else $('age').textContent='starting server…';
        };

        // ---- advertised mods ----
        function modsPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">Mods offered to players when they join. '+
            'Nothing is pushed &mdash; each player sees this list and chooses. '+
            'Prefer uploading the jar here: players then fetch it straight from this server. '+
            'External URLs must be <code>https://</code>; a SHA-256 pins the exact file.</p>'+
            '<div id="modsettings" class="muted" style="margin-bottom:10px"></div>'+
            '<div id="modlist"></div>'+
            '<section style="margin-top:14px"><h2>Upload a jar to this server</h2>'+
            '<p class="muted">Stored in <code>config/almin/modfiles/</code>. Players download it '+
            'over their game connection &mdash; no public link, nothing else to host.</p>'+
            '<input type="file" id="m-file" accept=".jar">'+
            '<button class="btn" id="m-upload" style="margin-top:8px">Upload</button>'+
            '<div class="msg" id="m-upmsg"></div>'+
            '<div id="m-files" style="margin-top:10px"></div></section>'+
            '<section style="margin-top:14px"><h2>Advertise a mod</h2>'+
            '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">'+
            '<input id="m-id" placeholder="mod id (e.g. sodium)">'+
            '<input id="m-name" placeholder="display name (optional)">'+
            '<input id="m-ver" placeholder="version (optional)">'+
            '<input id="m-sha" placeholder="sha256 (optional, recommended)">'+
            '</div>'+
            '<p class="muted" style="margin:10px 0 4px">Source &mdash; pick an uploaded file, '+
            'or leave it on &ldquo;URL&rdquo; and paste an https link.</p>'+
            '<select id="m-src" style="width:100%;background:#0b0d11;border:1px solid var(--line);'+
            'color:var(--ink);border-radius:8px;padding:9px 11px;font:inherit"></select>'+
            '<input id="m-url" placeholder="https://... direct link to the .jar" style="margin-top:8px">'+
            '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:8px">'+
            '<input type="checkbox" id="m-req" style="width:auto"> Required '+
            '(declining can disconnect, if mods-deny-kicks is on)</label>'+
            '<button class="btn" id="m-save" style="margin-top:10px">Save mod</button>'+
            '<div class="msg" id="m-msg"></div></section>';
          setTimeout(()=>{ loadMods(); loadModFiles();
            $('m-save').onclick=saveMod; $('m-upload').onclick=uploadMod;
            $('m-src').onchange=()=>{ $('m-url').style.display=$('m-src').value?'none':''; }; },0);
          return wrap;
        }
        async function loadMods(){
          const r=await jget('/api/mods');
          const box=$('modlist'); if(!box) return;
          if(r.status!==200){ box.innerHTML='<div class="note">'+esc(r.body.error||'unavailable')+'</div>'; return; }
          const s=$('modsettings');
          if(s){ s.innerHTML='';
            s.append(cfgToggle('mods-advertise','Advertise on join',r.body.advertise,loadMods),
                     cfgToggle('mods-deny-kicks','Declining disconnects',r.body.denyKicks,loadMods),
                     cfgToggle('require-client-mod','Almin required to play',r.body.requireClientMod,loadMods)); }
          const mods=r.body.mods||[];
          if(!mods.length){ box.innerHTML='<div class="note">Nothing advertised yet.</div>'; return; }
          box.innerHTML='';
          const sec=document.createElement('section');
          sec.innerHTML='<h2>Advertised ('+mods.length+')</h2>';
          for(const m of mods){
            const row=document.createElement('div'); row.className='row';
            const left=document.createElement('span'); left.className='k';
            left.innerHTML='<b style="color:var(--ink)">'+esc(m.name||m.id)+'</b>'+
              (m.version?' <span class="muted">'+esc(m.version)+'</span>':'')+
              (m.required?' <span class="state warn">REQUIRED</span>':' <span class="muted">optional</span>')+
              (m.sha256?' <span class="muted">&middot; pinned</span>':'')+
              '<br><span class="muted" style="font-size:12px">'+
              (m.file? 'served by this server &middot; modfiles/'+esc(m.file) : esc(m.url))+'</span>';
            const btn=document.createElement('button'); btn.className='btn danger'; btn.textContent='Remove';
            btn.style.marginLeft='auto';
            btn.onclick=async()=>{ if(!confirm('Stop advertising '+m.id+'?')) return;
              const d=await jpost('/api/mods/delete',{id:m.id});
              if(d.status!==200) alert(d.body.error||'remove failed'); loadMods(); };
            const edit=document.createElement('button'); edit.className='btn'; edit.textContent=m.required?'Make optional':'Make required';
            edit.onclick=async()=>{ const d=await jpost('/api/mods/save',{
                id:m.id,name:m.name,version:m.version,url:m.url,file:m.file,
                sha256:m.sha256,required:!m.required});
              if(d.status!==200) alert(d.body.error||'update failed'); loadMods(); };
            row.append(left,edit,btn);
            row.style.gap='8px'; row.style.alignItems='center';
            sec.appendChild(row);
          }
          box.appendChild(sec);
        }
        async function loadModFiles(){
          const r=await jget('/api/mods/files');
          const sel=$('m-src'), box=$('m-files');
          if(!sel) return;
          const files=(r.status===200 && r.body.files)?r.body.files:[];
          sel.innerHTML='<option value="">URL (external https link)</option>'+
            files.map(f=>'<option value="'+esc(f)+'">server file: '+esc(f)+'</option>').join('');
          $('m-url').style.display=sel.value?'none':'';
          if(box){
            box.innerHTML = files.length
              ? '<div class="muted">On this server: '+files.map(f=>
                  '<span style="display:inline-flex;gap:6px;align-items:center;margin:2px 8px 2px 0">'+
                  esc(f)+' <a href="#" data-f="'+esc(f)+'" class="delfile" style="color:#e97070">remove</a></span>').join('')+'</div>'
              : '<div class="note">No jars uploaded yet.</div>';
            box.querySelectorAll('.delfile').forEach(a=>a.onclick=async e=>{
              e.preventDefault();
              const f=a.getAttribute('data-f');
              if(!confirm('Delete '+f+' from the server?')) return;
              const d=await jpost('/api/mods/files/delete',{name:f});
              if(d.status!==200) alert(d.body.error||'delete failed');
              loadModFiles(); loadMods(); });
          }
        }
        async function uploadMod(){
          const inp=$('m-file'), msg=$('m-upmsg');
          if(!inp.files || !inp.files.length){ msg.className='msg err'; msg.textContent='Choose a .jar first.'; return; }
          const f=inp.files[0];
          msg.className='msg'; msg.textContent='Uploading '+f.name+'…';
          try{
            const r=await fetch('/api/mods/upload?name='+encodeURIComponent(f.name),
              {method:'POST',credentials:'same-origin',
               headers:{'Content-Type':'application/octet-stream'},body:f});
            const b=await r.json().catch(()=>({}));
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent = r.status===200 ? ('uploaded '+b.name+' ('+b.bytes+' bytes)')
                                             : (b.error||'upload failed');
            if(r.status===200){ inp.value=''; loadModFiles(); }
          }catch(e){ msg.className='msg err'; msg.textContent='upload failed'; }
        }
        async function saveMod(){
          const msg=$('m-msg');
          const src=$('m-src').value;
          const r=await jpost('/api/mods/save',{
            id:$('m-id').value.trim(), name:$('m-name').value.trim(),
            version:$('m-ver').value.trim(), sha256:$('m-sha').value.trim(),
            file:src, url:src?'':$('m-url').value.trim(), required:$('m-req').checked});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent = r.status===200 ? 'saved' : (r.body.error||'save failed');
          if(r.status===200){ ['m-id','m-name','m-ver','m-sha','m-url'].forEach(i=>$(i).value='');
            $('m-req').checked=false; $('m-src').value=''; $('m-url').style.display='';
            loadMods(); loadModFiles(); }
        }

        // ---- polling ----
        async function refreshOnce(){
          const s=await jget('/api/session');
          if(s.status!==200) return;
          authed=!!s.body.authed; secure=!!s.body.secure; pwSet=!!s.body.passwordSet;
          encrypted=!!s.body.encrypted;
          publicMetrics=!!s.body.publicMetrics; canStart=!!s.body.canStart;
          supervisor=!!s.body.supervisor;
          if(s.body.serverRunning!=null) serverRunning=!!s.body.serverRunning;
        }
        async function poll(){
          const wasAuthed=authed, wasRunning=serverRunning;
          try { await refreshOnce(); }
          catch(e){ $('age').textContent='panel unreachable'; return; }
          let d=null;
          if(authed){ const r=await jget('/api/state'); if(r.status===200) d=r.body; }
          else if(publicMetrics){ const r=await jget('/api/public'); if(r.status===200) d=r.body; }
          if(d){
            last=d;
            if(d.metrics && serverRunning){
              tpsHistory.push(d.metrics.tps);
              if(tpsHistory.length>40) tpsHistory.shift();
            }
            const secs=Math.max(0,Math.round((Date.now()-d.generated)/1000));
            $('age').textContent='updated '+(secs<2?'just now':secs+'s ago');
          }
          // Only rebuild the whole panel when login or server state flips, so a
          // half-typed password or a scrolled console isn't thrown away.
          if(authed!==wasAuthed || serverRunning!==wasRunning){
            if(!authed) tab='dash';
            if(!serverRunning) tpsHistory=[];
            render(); return;
          }
          setChrome();
          if(tab==='dash') updateMetrics();
          else if(tab==='log'||tab==='term') loadConsole();
          else if(tab==='players') loadPlayers();
          else if(tab==='activity') loadActivity();
        }
        $('logout').onclick=async()=>{ await jpost('/api/logout',{}); authed=false; tab='dash'; last=null; render(); };
        (async()=>{ await refreshOnce(); render(); poll(); setInterval(poll,3000); })();
        </script>
        """;
}
