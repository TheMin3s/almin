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
    private static final String PART1 = """
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
          input:disabled,select:disabled,textarea:disabled{opacity:.5;cursor:not-allowed}
          textarea{font:12px/1.5 ui-monospace,Menlo,monospace;min-height:46vh;resize:vertical}
          .term{display:flex;gap:8px;margin-top:12px}
          .term input{font:12px/1.5 ui-monospace,Menlo,monospace}
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
          .mapwrap{background:var(--card);border:1px solid var(--line);border-radius:12px;
                   padding:10px;position:relative}
          /* Direct child only. The legend draws the same marker shapes inline
             to key them, and a descendant selector blew each one up to the
             full width of the map. */
          .mapwrap > svg{display:block;width:100%;height:min(60vh,560px);
                       background:#0b0d11;border-radius:8px}
          .legend svg{width:15px;height:15px;display:inline-block;vertical-align:-3px}
          /* ---- the timeline map ---- */
          .maplayout{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;align-items:start}
          .maplayout.side{grid-template-columns:minmax(0,1fr) 310px}
          @media(max-width:1080px){.maplayout.side{grid-template-columns:minmax(0,1fr)}}
          .mapwrap > svg{cursor:grab;touch-action:none}
          .mapwrap > svg.grabbing{cursor:grabbing}
          .onlinebar{position:absolute;left:12px;top:12px;right:64px;display:flex;gap:6px;
                     flex-wrap:wrap;max-height:74px;overflow:hidden}
          .who{display:inline-flex;align-items:center;gap:6px;background:rgba(11,13,17,.84);
               border:1px solid var(--line);border-radius:999px;padding:2px 10px 2px 2px;
               font-size:12px;font-weight:600;cursor:pointer;white-space:nowrap}
          .who:hover{border-color:var(--brand)}
          .who.afk{opacity:.42}
          .who.on{border-color:var(--brand);color:var(--brand)}
          .who .face{width:19px;height:19px;border-radius:999px}
          .mapbtns{position:absolute;right:12px;top:12px;display:flex;flex-direction:column;gap:5px}
          .mapbtns.bottom{top:auto;bottom:12px}
          .mapbtns button{width:30px;height:30px;padding:0;line-height:1;display:flex;
                          align-items:center;justify-content:center;
                          background:rgba(11,13,17,.84);border:1px solid var(--line);
                          color:var(--ink);border-radius:8px;font-size:15px}
          .mapbtns button:hover{border-color:var(--brand)}
          .timeline{margin-top:10px}
          .timeline svg{display:block;width:100%;height:84px;background:#0b0d11;
                        border:1px solid var(--line);border-radius:9px;touch-action:none}
          .tlbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:9px}
          .tlbar .btn{padding:5px 11px;font-size:12.5px}
          .speed{display:flex;gap:2px;background:var(--card2);border:1px solid var(--line);
                 border-radius:8px;padding:2px}
          .speed button{background:none;border:0;color:var(--dim);padding:3px 8px;
                        border-radius:6px;font:inherit;font-size:12px;font-variant-numeric:tabular-nums}
          .speed button.on{background:var(--brand);color:#1a1205;font-weight:700}
          .mapside{background:var(--card);border:1px solid var(--line);border-radius:12px;
                   padding:11px 12px;max-height:min(62vh,560px);overflow:auto}
          .mapside h3{margin:0 0 8px;font-size:11px;text-transform:uppercase;
                      letter-spacing:.9px;color:var(--brand);font-weight:700}
          .sideact{display:grid;grid-template-columns:19px 1fr;gap:8px;padding:6px 2px;
                   border-bottom:1px solid rgba(255,255,255,.05);font-size:12.5px;cursor:pointer}
          .sideact:last-child{border-bottom:0}
          .sideact:hover{background:var(--card2)}
          .sideact .l1{display:flex;gap:6px;align-items:baseline;flex-wrap:wrap}
          .sideact .nm{font-weight:650}
          .sideact .tm{color:var(--mute);font-size:11px;margin-left:auto}
          .sideact .dt{color:var(--dim);word-break:break-word}
          .sideact.say .dt{color:var(--ink)}
          .maptip{position:absolute;pointer-events:none;background:#0b0d11;
                  border:1px solid var(--line);border-radius:6px;padding:4px 8px;font-size:12px;
                  color:var(--ink);white-space:nowrap;opacity:0;transition:opacity .1s;z-index:2}
          .legend{display:flex;flex-wrap:wrap;gap:10px;margin-top:9px;font-size:12px;color:var(--dim)}
          .legend i{display:inline-block;width:9px;height:9px;border-radius:50%;
                    margin-right:4px;vertical-align:-1px}
          .arow{display:grid;grid-template-columns:19px 52px 172px 106px minmax(0,1fr) auto;
                gap:10px;align-items:center;
                padding:5px 10px;border-bottom:1px solid rgba(255,255,255,.045);font-size:13px}
          .arow:last-child{border-bottom:0}
          .arow .ago{color:var(--mute);font-variant-numeric:tabular-nums}
          .arow .who{color:var(--ink);font-weight:600;overflow:hidden;text-overflow:ellipsis}
          .arow .what{font-weight:600}
          .arow .det{color:var(--dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
          .arow .at{color:var(--mute);font-size:12px;white-space:nowrap}
          @media(max-width:760px){.arow{grid-template-columns:19px 46px minmax(0,1fr);row-gap:2px}
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
          @media(max-width:760px){main{padding:16px 14px 30px}}

          /* ---- player faces ---- */
          /* Skins are 8x8 pixel art scaled up; smoothing them turns a face
             into a smudge, so every one of these is nearest-neighbour. */
          .face{width:26px;height:26px;border-radius:6px;flex:none;
                image-rendering:pixelated;image-rendering:crisp-edges;
                background:var(--card2);border:1px solid var(--line);object-fit:cover}
          .face.sm{width:19px;height:19px;border-radius:4px}
          .face.lg{width:34px;height:34px;border-radius:8px}
          /* The stand-in when there is no skin to be had: an initial on a
             colour derived from the name, so two players never look alike. */
          span.face{display:inline-flex;align-items:center;justify-content:center;
                    font-weight:700;font-size:12px;color:#0b0d11;line-height:1}
          span.face.sm{font-size:10px}
          span.face.lg{font-size:15px}

          /* ---- overlays, menus, chips ---- */
          .scrim{position:fixed;inset:0;background:rgba(6,8,11,.74);z-index:40;display:flex;
                 align-items:flex-start;justify-content:center;padding:38px 16px;overflow:auto}
          .modal{background:var(--card);border:1px solid var(--line);border-radius:14px;
                 width:min(880px,100%);padding:17px 20px 20px;
                 box-shadow:0 24px 60px rgba(0,0,0,.55)}
          .modal.wide{width:min(1100px,100%)}
          .modal h3{margin:0;font-size:15.5px;font-weight:650}
          .mtop{display:flex;align-items:center;gap:10px;margin-bottom:13px}
          .mtop .btn{margin-left:auto}
          .modal .grid2{display:grid;grid-template-columns:1fr 1fr;gap:9px}
          @media(max-width:620px){.modal .grid2{grid-template-columns:1fr}}
          .modal label.f{display:block;font-size:11.5px;text-transform:uppercase;
                         letter-spacing:.8px;color:var(--mute);font-weight:600;margin:11px 0 4px}
          .modal .row2{display:flex;gap:8px;align-items:center;margin-top:13px;flex-wrap:wrap}
          select{background:#0b0d11;border:1px solid var(--line);color:var(--ink);
                 border-radius:8px;padding:9px 11px;font:inherit;width:100%;outline:none}
          select:focus{border-color:var(--brand)}
          .menu{position:fixed;z-index:60;background:var(--card2);border:1px solid var(--line);
                border-radius:10px;padding:5px;min-width:206px;
                box-shadow:0 14px 34px rgba(0,0,0,.55)}
          .menu button{display:flex;gap:10px;align-items:center;width:100%;text-align:left;
                       background:none;border:0;color:var(--ink);padding:7px 10px;
                       border-radius:7px;font:inherit}
          .menu button:hover:not([disabled]){background:#333a45}
          .menu button[disabled]{opacity:.32;cursor:not-allowed}
          .menu button.danger:hover:not([disabled]){color:#ffb3b3}
          .menu b{font-size:10.5px;text-transform:uppercase;letter-spacing:.8px;
                  color:var(--mute);display:block;padding:6px 10px 3px}
          .menu hr{border:0;border-top:1px solid var(--line);margin:5px 3px}
          .menu .sub{color:var(--mute);font-size:11.5px;margin-left:auto;padding-left:12px}
          .countdown{display:flex;align-items:center;gap:18px;margin:18px 0 8px}
          .cdnum{font-size:44px;font-weight:650;letter-spacing:-1.5px;min-width:72px;
                 text-align:center;color:var(--brand);line-height:1}
          .cdbar{flex:1;height:8px;border-radius:99px;background:var(--track);overflow:hidden}
          .cdbar i{display:block;height:100%;border-radius:99px;background:var(--brand);
                   transition:width 1s linear}
          .chip{display:inline-block;padding:1px 7px;border-radius:5px;font-size:10.5px;
                font-weight:700;letter-spacing:.5px;text-transform:uppercase;
                border:1px solid var(--line);color:var(--dim);vertical-align:1px}
          .chip.jar{color:#8fd4b0;border-color:#315c49}
          .chip.link{color:#8ab6e8;border-color:#31485f}
          .chip.req{color:#f0c46a;border-color:#5e4b26}
          .icon{width:15px;height:15px;flex:none;vertical-align:-3px}
          .bartitle{display:flex;align-items:center;gap:10px;margin:0 0 12px;flex-wrap:wrap}
          .bartitle h2{margin:0}
          .bartitle .spacer{margin-left:auto}
          .cog{padding:6px 9px;line-height:1}

          /* ---- file browser ---- */
          .crumbs{display:flex;align-items:center;gap:1px;flex-wrap:wrap;font-size:13px;min-width:0}
          .crumbs button{background:none;border:0;color:var(--dim);padding:4px 7px;
                         border-radius:6px;font:inherit}
          .crumbs button:hover{color:var(--ink);background:var(--card2)}
          .crumbs button:last-of-type{color:var(--ink);font-weight:600}
          .crumbs i{color:var(--mute);font-style:normal;font-size:11px}
          .browser{background:var(--card);border:1px solid var(--line);border-radius:12px;
                   overflow:hidden}
          .frow{display:grid;grid-template-columns:34px minmax(0,1fr) 118px 92px 150px;
                gap:14px;align-items:center;padding:11px 16px;
                border-bottom:1px solid rgba(255,255,255,.05)}
          .frow:last-child{border-bottom:0}
          .frow:hover{background:var(--card2)}
          .frow.pick{background:#232a35}
          .frow .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}
          .frow .meta{color:var(--mute);font-size:12px;font-variant-numeric:tabular-nums}
          .frow .kind{color:var(--dim);font-size:12px}
          .frow.up .nm{color:var(--brand)}
          .fico{width:26px;height:26px;display:flex;align-items:center;justify-content:center;
                border-radius:7px;background:#0e1116;border:1px solid var(--line);font-size:13px}
          .fempty{padding:34px 16px;text-align:center;color:var(--mute);font-style:italic}
          /* Reaches to the bottom of the window so that "right-click anywhere"
             means anywhere, and not only the few pixels a short listing
             happens to leave inside the box. */
          .filespanel{min-height:calc(100vh - 190px)}
          @media(max-width:900px){.frow{grid-template-columns:34px minmax(0,1fr) 92px}
                                  .frow .kind,.frow .when{display:none}}
          @media(max-width:600px){.frow{grid-template-columns:34px minmax(0,1fr)}
                                  .frow .sz{display:none}}

          /* ---- mods ---- */
          .modrow{display:flex;gap:14px;align-items:center;padding:13px 16px;
                  border-bottom:1px solid rgba(255,255,255,.05)}
          .modrow:last-child{border-bottom:0}
          .modrow .body{min-width:0;flex:1}
          .modrow .ttl{font-weight:650;display:flex;gap:7px;align-items:center;flex-wrap:wrap}
          .modrow .sub{color:var(--mute);font-size:12px;margin-top:2px;
                       overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
          .modicon{width:46px;height:46px;border-radius:11px;flex:none;object-fit:cover;
                   background:#0e1116;border:1px solid var(--line)}
          span.modicon{display:flex;align-items:center;justify-content:center;
                       font-size:19px;font-weight:700;color:var(--mute)}
          .modrow .acts{display:flex;gap:7px;flex:none}
          @media(max-width:620px){.modrow{flex-wrap:wrap}.modrow .acts{width:100%}}
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
        // Whether to ask the server for player faces at all. Off means the
        // lists draw initials instead and nothing is requested.
        let headsOn=true;
        let tab='dash', last=null, stuck=true, tpsHistory=[];
        // The panel is served out of the mod jar, so an update replaces it.
        // These track the version this page came from and whether we are
        // waiting for a restarted server to answer again, which is what lets
        // an open tab put itself onto the new panel instead of sitting there
        // showing an old one.
        let version=null, restarting=false, awaitingReturn=false, wasReachable=true;
        let startCommand='', startProblem='', relaunchError='', waitingSince=0;
        // Long enough for a big world to boot; short enough that a restart
        // which is never coming back stops pretending it is.
        const WAIT_LIMIT=5*60*1000;

        const esc = s => (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
        async function jget(u){ const r=await fetch(u,{credentials:'same-origin'});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }
        async function jpost(u,d){ const r=await fetch(u,{method:'POST',credentials:'same-origin',
            headers:{'Content-Type':'application/json'},body:JSON.stringify(d||{})});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }

        // ---- shared pieces: faces, menus, overlays, formatting ----

        // A hue derived from the name, so a player with no skin still gets
        // something that is theirs rather than one shared grey box.
        function nameHue(s){
          let h=0; for(let i=0;i<(s||'').length;i++) h=(h*31+s.charCodeAt(i))|0;
          return Math.abs(h)%360;
        }
        /**
         * A player's face. An <img> when heads are on, falling back to an
         * initial the moment the request 404s — which is the ordinary answer
         * for a player whose skin the server could not find, so the fallback
         * is the common path and not an error case.
         */
        function avatar(name,uuid,size){
          const cls='face'+(size?' '+size:'');
          const letter=((name||'?').trim().charAt(0)||'?').toUpperCase();
          const stand=()=>{ const s=document.createElement('span'); s.className=cls;
            s.textContent=letter; s.style.background='hsl('+nameHue(name)+' 45% 62%)';
            s.title=name||''; return s; };
          if(!headsOn || !uuid) return stand();
          const img=document.createElement('img'); img.className=cls;
          img.alt=''; img.loading='lazy'; img.title=name||'';
          img.src='/api/head?uuid='+encodeURIComponent(uuid)+
                  '&name='+encodeURIComponent(name||'');
          img.onerror=()=>{ if(img.parentNode) img.parentNode.replaceChild(stand(),img); };
          return img;
        }

        let openMenu=null, openScrim=null, onScrimClose=null;
        function closeMenu(){ if(openMenu){ openMenu.remove(); openMenu=null; } }
        function closeModal(){
          if(!openScrim) return;
          openScrim.remove(); openScrim=null;
          // Whoever opened it may have something running on its behalf — a
          // countdown, say — that has no business outliving the dialog.
          const done=onScrimClose; onScrimClose=null;
          if(done) done();
        }
        document.addEventListener('keydown',e=>{
          if(e.key!=='Escape') return;
          if(openMenu) closeMenu(); else closeModal();
        });
        window.addEventListener('resize',closeMenu);

        /**
         * A context menu at a point on screen. Items are
         * {label, icon, hint, run, danger, disabled, why}, the string 'sep',
         * or {header}.
         */
        function menu(x,y,items){
          closeMenu();
          const m=document.createElement('div'); m.className='menu';
          for(const it of items){
            if(it==='sep'){ m.appendChild(document.createElement('hr')); continue; }
            if(it.header){ const b=document.createElement('b'); b.textContent=it.header;
              m.appendChild(b); continue; }
            const b=document.createElement('button');
            b.innerHTML=(it.icon||'')+'<span>'+esc(it.label)+'</span>'+
              (it.hint?'<span class="sub">'+esc(it.hint)+'</span>':'');
            if(it.danger) b.className='danger';
            if(it.disabled){ b.disabled=true; if(it.why) b.title=it.why; }
            else b.onclick=()=>{ closeMenu(); it.run(); };
            m.appendChild(b);
          }
          document.body.appendChild(m);
          // Flip rather than clip: a menu opened near an edge stays whole.
          const r=m.getBoundingClientRect();
          m.style.left=Math.max(6,Math.min(x,window.innerWidth-r.width-6))+'px';
          m.style.top=Math.max(6,Math.min(y,window.innerHeight-r.height-6))+'px';
          openMenu=m;
          // Added next tick, or the very click that opened this would close it.
          setTimeout(()=>document.addEventListener('click',closeMenu,{once:true}),0);
          return m;
        }
        function menuUnder(btn,items){
          const r=btn.getBoundingClientRect();
          menu(r.left,r.bottom+6,items);
        }
        function menuAt(ev,items){
          ev.preventDefault(); ev.stopPropagation();
          menu(ev.clientX,ev.clientY,items);
        }

        /** An overlay. `build(body, close)` fills it in. */
        function modal(title,build,opts){
          closeModal(); closeMenu();
          const scrim=document.createElement('div'); scrim.className='scrim';
          const box=document.createElement('div');
          box.className='modal'+((opts&&opts.wide)?' wide':'');
          const top=document.createElement('div'); top.className='mtop';
          const h=document.createElement('h3'); h.id='modal-title'; h.textContent=title;
          const x=document.createElement('button'); x.className='btn'; x.textContent='Close';
          x.onclick=closeModal;
          top.append(h,x);
          const body=document.createElement('div'); body.id='modal-body';
          // Named, so anything that wants to replace what is in the dialog can
          // ask for it rather than guess at the structure from the outside.
          box.append(top,body);
          scrim.appendChild(box);
          scrim.onclick=e=>{ if(e.target===scrim) closeModal(); };
          document.body.appendChild(scrim);
          openScrim=scrim;
          onScrimClose=(opts&&opts.onClose)||null;
          build(body,closeModal);
          return body;
        }

        function fmtBytes(n){
          if(n==null||n<0) return '';
          if(n<1024) return n+' B';
          const u=['KB','MB','GB','TB']; let v=n/1024, i=0;
          while(v>=1024 && i<u.length-1){ v/=1024; i++; }
          return (v>=100?v.toFixed(0):v>=10?v.toFixed(1):v.toFixed(2))+' '+u[i];
        }
        function fmtWhen(ts){
          if(!ts) return '';
          const d=new Date(ts), now=Date.now();
          const clock=d.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});
          if(new Date(now).toDateString()===d.toDateString()) return clock;
          if(now-ts < 300*24*3600*1000)
            return d.toLocaleDateString([],{month:'short',day:'numeric'})+' '+clock;
          return d.toLocaleDateString();
        }
        /** Clipboard where it works, a selectable prompt where it does not. */
        function copyText(t){
          if(navigator.clipboard && window.isSecureContext){
            navigator.clipboard.writeText(t).catch(()=>prompt('Path:',t));
          } else prompt('Path:',t);
        }

        const ICON={
          folder:'<svg class="icon" viewBox="0 0 16 16" fill="currentColor"><path d="M1.6 4A1.6 1.6 0 0 1 3.2 2.4h2.6L7.2 4h5.6A1.6 1.6 0 0 1 14.4 5.6v6A1.6 1.6 0 0 1 12.8 13.2H3.2A1.6 1.6 0 0 1 1.6 11.6z"/></svg>',
          file:'<svg class="icon" viewBox="0 0 16 16" fill="currentColor"><path d="M3.4 1.8h5L13 6.2v8A1.4 1.4 0 0 1 11.6 15.6H3.4A1.4 1.4 0 0 1 2 14.2V3.2A1.4 1.4 0 0 1 3.4 1.8zm5 1.3v3.3H12z"/></svg>',
          edit:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M11.5 2.5l2 2L6 12l-3 1 1-3z"/><path d="M2.5 14.5h11"/></svg>',
          down:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 2v8"/><path d="M4.5 7L8 10.5 11.5 7"/><path d="M2.5 13.5h11"/></svg>',
          up:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 11V3"/><path d="M4.5 6.5L8 3l3.5 3.5"/><path d="M2.5 13.5h11"/></svg>',
          trash:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2.8 4.2h10.4"/><path d="M6.2 4.2V2.6h3.6v1.6"/><path d="M4.2 4.2l.7 9.2h6.2l.7-9.2"/></svg>',
          rename:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 2.6h4"/><path d="M8 2.6v10.8"/><path d="M6 13.4h4"/><path d="M1.8 5.6h3.4M10.8 5.6h3.4"/></svg>',
          plus:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M8 3v10M3 8h10"/></svg>',
          cog:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="8" cy="8" r="2.3"/><path d="M8 1.5v1.8M8 12.7v1.8M1.5 8h1.8M12.7 8h1.8M3.4 3.4l1.3 1.3M11.3 11.3l1.3 1.3M12.6 3.4l-1.3 1.3M4.7 11.3l-1.3 1.3"/></svg>',
          globe:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="8" cy="8" r="6"/><path d="M2 8h12M8 2c1.9 2 1.9 10 0 12M8 2C6.1 4 6.1 12 8 14"/></svg>',
          box:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M8 1.7l5.6 2.9v6.8L8 14.3 2.4 11.4V4.6z"/><path d="M2.4 4.6L8 7.5l5.6-2.9M8 7.5v6.8"/></svg>',
          refresh:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M13.4 8a5.4 5.4 0 1 1-1.6-3.8"/><path d="M13.6 2.4v3.4h-3.4"/></svg>',
          pencilnew:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.2 2.2h5L12.8 6.6v3"/><path d="M8.2 2.2v4.4h4.6"/><path d="M3.2 2.2v11.6h4.4"/><path d="M10 13.6h4M12 11.6v4"/></svg>',
          foldernew:'<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M1.9 12V4.3h3.9L7 5.9h5.2v2.3"/><path d="M1.9 12h6"/><path d="M12.4 10v4.4M10.2 12.2h4.4"/></svg>'
        };

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

        // Replaces the page while the server is away, so a restart looks like a
        // restart rather than like a panel that broke.
        function showWaiting(){
          if($('waiting')) return;
          const m=$('main'); m.innerHTML='';
          const b=document.createElement('div'); b.className='banner'; b.id='waiting';
          b.innerHTML='<span class="state warn">Restarting</span><span class="muted">'+
            'The server is starting again. This page reconnects on its own — '+
            'no need to reload it.</span>';
          m.appendChild(b);
        }

        function setChrome(){
          const st=$('status'), txt=$('statustext');
          st.className='pill '+(serverRunning?'up':'down');
          txt.textContent=serverRunning?'Online':'Stopped';
          st.title=serverRunning?'Minecraft server is running':'Minecraft server is stopped';
          $('logout').style.display=authed?'':'none';
          $('srvstop').style.display=(authed&&serverRunning)?'':'none';
          $('srvrestart').style.display=(authed&&serverRunning)?'':'none';
          $('srvrestart').title=canStart?('Stop, then start it again here: '+startCommand)
                                        :'Stops the server; your wrapper starts it again';
          $('srvstart').style.display=(authed&&!serverRunning)?'':'none';
          $('srvstart').disabled=!canStart;
          $('srvstart').title=canStart?('Runs: '+startCommand)
                                      :(startProblem||'No way to start the server from here');
          const nav=$('nav'); nav.innerHTML='';
          const tabs = authed ? [['dash','Overview'],['term','Console'],
                                 ['activity','Activity'],['files','Files'],['players','Players'],
                                 ['mods','Mods'],['settings','Settings']]
                              : [['dash','Overview']];
          for(const [id,label] of tabs){
            const b=document.createElement('button'); b.className=(id===tab?'on':'');
            // Settings is the one tab that is a place rather than a subject,
            // so it gets the cog everything else has learned to look for.
            if(id==='settings'){ b.innerHTML=ICON.cog+' '+esc(label); b.title='Settings'; }
            else b.textContent=label;
            b.onclick=()=>{ tab=id; render(); }; nav.appendChild(b);
          }
        }

        function render(){
          setChrome();
          const m=$('main'); m.innerHTML='';
          if(!authed && tab!=='dash') tab='dash';
          if(tab==='dash') m.appendChild(dashPanel());
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
              (authed?(canStart?'Use <b>Start server</b> above.':esc(startProblem||'Nothing here can start it.'))
                     :'Live metrics resume when it starts.')+'</span>';
            wrap.appendChild(b);
          }
          if(authed && relaunchError){
            const b=document.createElement('div'); b.className='banner';
            b.style.borderLeftColor='#e0503f';
            b.innerHTML='<span class="state crit">Restart failed</span><span class="muted">'+
              'The server was stopped for a restart, but starting it again failed: '+
              esc(relaunchError)+'</span>';
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

        async function loadConsole(){
          if(tab!=='term') return;
          if(!serverRunning) return;
          const r=await jget('/api/console'); const pre=$('log'); if(!pre) return;
          pre.innerHTML=(r.body.lines||[]).map(l=>{
            const c=/\\/ERROR\\]| ERROR /.test(l)?'errline':/\\/WARN\\]| WARN /.test(l)?'warnline':'';
            return c?'<span class="'+c+'">'+esc(l)+'</span>':esc(l); }).join('\\n');
          if(stuck) pre.scrollTop=pre.scrollHeight;
        }

        function termPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">The server log, live. Type below to run a command as '+
            'the console (the same as <code>/almin op cmd</code>) — no leading slash needed.</p>';
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
          // Follows the tail unless you scroll up, same as the old Console tab.
          pre.addEventListener('scroll',()=>{
            stuck = pre.scrollTop+pre.clientHeight >= pre.scrollHeight-24; });
          bar.append(inp,btn); wrap.append(bar,msg,pre);
          loadConsole();
          return wrap;
        }

        """;

    /**
     * The file browser: listing, context menus, and the overlays they open.
     *
     * <p>Split for the same reason as the others — see {@link #HTML}.
     */
    private static final String PARTFILES = """
        // ---- files ----
        let curDir='', dirWritable=false, dirRoots='', dirEntries=[];

        const KINDS={jar:'Java archive',json:'JSON',txt:'Text',log:'Log',yml:'YAML',yaml:'YAML',
          properties:'Properties',png:'Image',jpg:'Image',jpeg:'Image',gif:'Image',webp:'Image',
          zip:'Archive',gz:'Archive',xz:'Archive',mca:'Region data',mcr:'Region data',
          dat:'NBT data',nbt:'NBT data',toml:'TOML',sh:'Shell script',bat:'Batch script',
          js:'JavaScript',html:'HTML',css:'CSS',mcmeta:'Pack metadata',md:'Markdown',
          conf:'Config',cfg:'Config',ini:'Config',lock:'Lock file',db:'Database'};
        /** Things a textarea would only mangle. */
        const BINARY=new Set(['jar','zip','gz','xz','7z','rar','zst','png','jpg','jpeg','gif',
          'webp','ico','mca','mcr','dat','dat_old','nbt','class','so','dll','dylib','bin','db',
          'ttf','otf','wav','ogg','mp3','pdf','jfr','hprof']);
        function extOf(name){
          const i=(name||'').lastIndexOf('.');
          return i<=0 ? '' : name.slice(i+1).toLowerCase();
        }
        function kindOf(name){
          const e=extOf(name);
          if(!e) return 'File';
          return KINDS[e] || (e.length<=7 ? e.toUpperCase()+' file' : 'File');
        }
        function isText(name){ const e=extOf(name); return !e || !BINARY.has(e); }

        function filesPanel(){
          const wrap=document.createElement('div');
          wrap.className='filespanel';
          wrap.innerHTML=
            '<div class="bartitle">'+
              '<div class="crumbs" id="fcrumbs"></div>'+
              '<span class="spacer"></span>'+
              '<span class="muted num" id="fcount"></span>'+
              '<button class="btn" id="fadd">'+ICON.plus+' New</button>'+
              '<button class="btn cog" id="frefresh" title="Reload this folder">'+
                ICON.refresh+'</button>'+
            '</div>'+
            '<p class="muted" id="fhint" style="margin:-5px 0 12px"></p>'+
            '<div class="browser" id="flist"></div>'+
            '<div class="msg" id="fmsg"></div>';
          setTimeout(()=>{
            $('fadd').onclick=()=>menuUnder($('fadd'),addMenu());
            $('frefresh').onclick=()=>loadDir(curDir);
            // Right-clicking anywhere that is not a row is the same as the
            // + button: it is about the folder, not about any one thing in it.
            // On the whole panel rather than on the list, because a full
            // folder leaves no empty list to click.
            wrap.oncontextmenu=e=>{
              if(e.target.closest('.frow:not(.up)')) return;
              menuAt(e,addMenu());
            };
            loadDir(curDir);
          },0);
          return wrap;
        }

        function fmsg(text,cls){
          const m=$('fmsg'); if(!m) return;
          m.className='msg'+(cls?' '+cls:''); m.textContent=text||'';
        }

        function crumbs(){
          const box=$('fcrumbs'); if(!box) return;
          box.innerHTML='';
          const parts=curDir?curDir.split('/').filter(Boolean):[];
          const add=(label,path,last)=>{
            const b=document.createElement('button'); b.textContent=label;
            if(!last) b.onclick=()=>loadDir(path);
            box.appendChild(b);
            if(!last){ const i=document.createElement('i'); i.textContent='/';
              box.appendChild(i); }
          };
          add('server root','',parts.length===0);
          let sofar='';
          parts.forEach((seg,n)=>{
            sofar = sofar ? sofar+'/'+seg : seg;
            add(seg,sofar,n===parts.length-1);
          });
        }

        function fileIcon(e){
          const d=document.createElement('div'); d.className='fico';
          if(e.directory){ d.innerHTML=ICON.folder; d.style.color='var(--brand)'; }
          else if(extOf(e.name)==='jar'){ d.innerHTML=ICON.box; d.style.color='#8fd4b0'; }
          else { d.innerHTML=ICON.file; d.style.color='var(--mute)'; }
          return d;
        }

        function fullPath(name){ return curDir ? curDir+'/'+name : name; }

        /** What right-clicking one row offers. */
        function entryMenu(e){
          const path=fullPath(e.name);
          const readOnly='Read-only here — writes are limited to '+
            (dirRoots||'the configured roots');
          const items=[{header:e.name}];
          if(e.directory){
            items.push({label:'Open',icon:ICON.folder,run:()=>loadDir(path)});
          } else {
            const big=e.size>2*1024*1024;
            items.push({label:'Edit',icon:ICON.edit,
              disabled:!isText(e.name)||big,
              why:big?'Larger than the 2 MB the editor can open'
                     :'Not a text file — download it instead',
              run:()=>openEditor(path,false,e.writable)});
            items.push({label:'Download',icon:ICON.down,run:()=>dlFile(path)});
          }
          items.push('sep');
          items.push({label:'Rename…',icon:ICON.rename,disabled:!e.writable,
            why:readOnly,run:()=>renameDialog(path,e.name)});
          items.push({label:'Delete…',icon:ICON.trash,danger:true,disabled:!e.writable,
            why:readOnly,run:()=>deleteDialog(path,e)});
          items.push('sep');
          items.push({label:'Copy path',run:()=>copyText('/'+path)});
          return items;
        }

        /** What right-clicking the folder itself, or the + button, offers. */
        function addMenu(){
          const why='This folder is read-only — writes are limited to '+
            (dirRoots||'the configured roots');
          return [
            {header:'Add to '+(curDir?'/'+curDir:'the server root')},
            {label:'Upload files…',icon:ICON.up,disabled:!dirWritable,why,run:uploadDialog},
            {label:'Download from a link…',icon:ICON.globe,disabled:!dirWritable,why,
             run:fetchDialog},
            'sep',
            {label:'New file…',icon:ICON.pencilnew,disabled:!dirWritable,why,
             run:()=>openEditor('',true)},
            {label:'New folder…',icon:ICON.foldernew,disabled:!dirWritable,why,
             run:mkdirDialog}
          ];
        }

        async function loadDir(path){
          // A menu belongs to what was on screen a moment ago; leaving it up
          // over a different folder would offer the wrong thing.
          closeMenu();
          curDir=path;
          const r=await jget('/api/files?path='+encodeURIComponent(path));
          const box=$('flist'); if(!box) return;
          fmsg('');
          if(r.status!==200){
            box.innerHTML='<div class="fempty">'+esc(r.body.error||'unavailable')+'</div>';
            return;
          }
          dirWritable=!!r.body.writable; dirRoots=r.body.roots||'';
          dirEntries=r.body.entries||[];
          crumbs();
          const hint=$('fhint');
          if(hint) hint.textContent = dirWritable
            ? 'Right-click anything for what you can do with it.'
            : 'Read-only here. Writes are limited to '+(dirRoots||'the configured roots')+
              ' (and each world’s datapacks folder).';
          const count=$('fcount');
          if(count){
            const dirs=dirEntries.filter(e=>e.directory).length;
            count.textContent=dirEntries.length
              ? dirs+' folder'+(dirs===1?'':'s')+' · '+
                (dirEntries.length-dirs)+' file'+(dirEntries.length-dirs===1?'':'s')
              : '';
          }
          box.innerHTML='';
          if(path){
            const up=document.createElement('div'); up.className='frow up';
            const ico=document.createElement('div'); ico.className='fico';
            ico.innerHTML=ICON.up; ico.style.color='var(--brand)';
            const nm=document.createElement('div'); nm.className='nm'; nm.textContent='… up one folder';
            up.append(ico,nm);
            up.onclick=()=>loadDir(path.split('/').slice(0,-1).join('/'));
            box.appendChild(up);
          }
          if(!dirEntries.length){
            const empty=document.createElement('div'); empty.className='fempty';
            empty.textContent=dirWritable
              ? 'This folder is empty. Right-click to put something in it.'
              : 'This folder is empty.';
            box.appendChild(empty);
            return;
          }
          for(const e of dirEntries) box.appendChild(fileRow(e));
        }

        function fileRow(e){
          const path=fullPath(e.name);
          const row=document.createElement('div'); row.className='frow';
          const nm=document.createElement('div'); nm.className='nm';
          nm.textContent=e.name; nm.title=e.name;
          if(e.directory) nm.style.color='var(--brand)';
          const kind=document.createElement('div'); kind.className='kind';
          kind.textContent=e.directory?'Folder':kindOf(e.name);
          const sz=document.createElement('div'); sz.className='meta sz';
          sz.textContent = e.directory
            ? (e.items<0?'':e.items+' item'+(e.items===1?'':'s'))
            : fmtBytes(e.size);
          const when=document.createElement('div'); when.className='meta when';
          when.textContent=fmtWhen(e.modified);
          row.append(fileIcon(e),nm,kind,sz,when);
          row.onclick=()=>{
            if(e.directory) return loadDir(path);
            if(isText(e.name) && e.size<=2*1024*1024) return openEditor(path,false,e.writable);
            dlFile(path);
          };
          row.oncontextmenu=ev=>menuAt(ev,entryMenu(e));
          return row;
        }

        // ---- what the menus open ----

        /**
         * The editor, as an overlay rather than a column. It used to sit
         * permanently beside the list taking half the width whether or not
         * anything was open; now the browser gets the whole page and the
         * editor appears when there is something to edit.
         */
        function openEditor(path,fresh,writable){
          const canWrite = fresh ? dirWritable : !!writable;
          modal(fresh?'New file':path,(body)=>{
            body.innerHTML=
              '<label class="f">Path</label>'+
              '<input id="fpath" placeholder="path under a writable root">'+
              '<label class="f">Contents</label>'+
              '<textarea id="fbody" placeholder="'+
              (fresh?'Type the contents, set the path, then Save.':'Loading…')+'"></textarea>'+
              '<div class="row2">'+
                '<button class="btn go" id="fsave">Save</button>'+
                '<button class="btn" id="fdl">Download</button>'+
                '<button class="btn" id="fren">Rename…</button>'+
                '<button class="btn danger" id="fdel">Delete…</button>'+
              '</div>'+
              '<div class="msg" id="emsg"></div>';
            $('fpath').value = fresh ? (curDir?curDir+'/':'') : path;
            $('fsave').onclick=saveFile;
            $('fdl').onclick=()=>dlFile($('fpath').value.trim());
            $('fren').onclick=()=>{
              const p=$('fpath').value.trim();
              renameDialog(p,p.split('/').pop());
            };
            $('fdel').onclick=()=>deleteDialog($('fpath').value.trim(),null);
            // Match the context menu: what the write rules forbid is not
            // offered here either, rather than offered and then refused.
            ['fren','fdel'].forEach(id=>$(id).disabled=fresh || !canWrite);
            $('fdl').disabled=!!fresh;
            if(!canWrite){
              $('fsave').disabled=true;
              const why='Read-only here — writes are limited to '+
                (dirRoots||'the configured roots');
              ['fsave','fren','fdel'].forEach(id=>$(id).title=why);
              const m=$('emsg'); m.className='msg'; m.textContent=why+'.';
            }
            if(fresh) $('fpath').focus(); else openFile(path);
          },{wide:true});
        }

        async function openFile(path){
          const r=await jget('/api/file?path='+encodeURIComponent(path));
          const msg=$('emsg'); if(!msg) return;
          if(r.status!==200){
            msg.className='msg err';
            msg.textContent=(r.body.error||'could not open')+
              ' — use Download for anything that is not text.';
            $('fbody').value='';
            return;
          }
          $('fpath').value=path; $('fbody').value=r.body.content;
          // The message line is left alone: anything in it was put there by
          // openEditor — the read-only note — and loading the file does not
          // make it untrue.
        }
        async function saveFile(){
          const r=await jpost('/api/file',{path:$('fpath').value,content:$('fbody').value});
          const msg=$('emsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'Saved.':(r.body.message||r.body.error||'save failed');
          if(r.body.ok) loadDir(curDir);
        }
        function dlFile(path){
          if(!path){ fmsg('Pick a file first.','err'); return; }
          // Same-origin GET, so the session cookie goes with it.
          location.href='/api/file/download?path='+encodeURIComponent(path);
        }

        function renameDialog(path,name){
          modal('Rename',(body,close)=>{
            body.innerHTML='<p class="muted">Renaming <code>/'+esc(path)+'</code>. '+
              'It stays in the same folder.</p>'+
              '<label class="f">New name</label><input id="rnname">'+
              '<div class="row2"><button class="btn go" id="rngo">Rename</button></div>'+
              '<div class="msg" id="rnmsg"></div>';
            const inp=$('rnname'); inp.value=name; inp.focus(); inp.select();
            const go=async()=>{
              const v=inp.value.trim();
              const msg=$('rnmsg');
              if(!v){ msg.className='msg err'; msg.textContent='Type a name.'; return; }
              const r=await jpost('/api/file/rename',{path:path,name:v});
              if(r.body.ok){ close(); loadDir(curDir); fmsg('Renamed to '+v+'.','ok'); }
              else { msg.className='msg err';
                msg.textContent=r.body.message||r.body.error||'rename failed'; }
            };
            inp.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); go(); } };
            $('rngo').onclick=go;
          });
        }

        function deleteDialog(path,entry){
          modal('Delete',(body,close)=>{
            const warn = entry && entry.directory
              ? 'Folders are only deleted when they are already empty.'
              : 'This cannot be undone.';
            body.innerHTML='<p>Delete <code>/'+esc(path)+'</code>?</p>'+
              '<p class="muted">'+esc(warn)+'</p>'+
              '<div class="row2"><button class="btn danger" id="dlgo">Delete</button>'+
              '<button class="btn" id="dlno">Cancel</button></div>'+
              '<div class="msg" id="dlmsg"></div>';
            $('dlno').onclick=close;
            $('dlgo').onclick=async()=>{
              const r=await jpost('/api/file/delete',{path:path});
              if(r.body.ok){ close(); loadDir(curDir); fmsg('Deleted '+path+'.','ok'); }
              else { const m=$('dlmsg'); m.className='msg err';
                m.textContent=r.body.message||r.body.error||'delete failed'; }
            };
          });
        }

        function mkdirDialog(){
          modal('New folder',(body,close)=>{
            body.innerHTML='<p class="muted">Created inside <code>'+
              esc(curDir?'/'+curDir:'the server root')+'</code>.</p>'+
              '<label class="f">Name</label><input id="mkname" placeholder="folder name">'+
              '<div class="row2"><button class="btn go" id="mkgo">Create</button></div>'+
              '<div class="msg" id="mkmsg"></div>';
            const inp=$('mkname'); inp.focus();
            const go=async()=>{
              const v=inp.value.trim(); const msg=$('mkmsg');
              if(!v){ msg.className='msg err'; msg.textContent='Type a name.'; return; }
              const r=await jpost('/api/file/mkdir',{path:curDir,name:v});
              if(r.body.ok){ close(); loadDir(curDir); fmsg('Created '+v+'.','ok'); }
              else { msg.className='msg err';
                msg.textContent=r.body.message||r.body.error||'could not create it'; }
            };
            inp.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); go(); } };
            $('mkgo').onclick=go;
          });
        }

        function uploadDialog(){
          modal('Upload files',(body,close)=>{
            body.innerHTML='<p class="muted">Landing in <code>'+
              esc(curDir?'/'+curDir:'the server root')+'</code>. '+
              'Pick more than one and they go up in order.</p>'+
              '<input type="file" id="fup" multiple>'+
              '<div class="row2"><button class="btn go" id="fupgo">Upload</button></div>'+
              '<div class="msg" id="fupmsg"></div>';
            $('fupgo').onclick=()=>upFiles(close);
          });
        }
        async function upFiles(close){
          const inp=$('fup'), msg=$('fupmsg'), btn=$('fupgo');
          if(!inp.files||!inp.files.length){
            msg.className='msg err'; msg.textContent='Choose a file first.'; return; }
          btn.disabled=true;
          let done=0, failed=0;
          for(const f of inp.files){
            msg.className='msg';
            msg.textContent='Uploading '+f.name+' ('+(done+1)+' of '+inp.files.length+')…';
            try{
              const r=await fetch('/api/file/upload?path='+
                  encodeURIComponent((curDir?curDir+'/':'')+f.name),
                {method:'POST',credentials:'same-origin',
                 headers:{'Content-Type':'application/octet-stream'},body:f});
              const b=await r.json().catch(()=>({}));
              if(r.status===200) done++;
              else { failed++; msg.className='msg err';
                msg.textContent=f.name+': '+(b.error||'upload failed ('+r.status+')');
                break; }
            }catch(err){
              failed++; msg.className='msg err';
              msg.textContent=f.name+': upload failed — '+err.message; break;
            }
          }
          btn.disabled=false;
          loadDir(curDir);
          if(!failed){ close(); fmsg('Uploaded '+done+' file'+(done===1?'':'s')+'.','ok'); }
        }

        function fetchDialog(){
          modal('Download a link to the server',(body,close)=>{
            body.innerHTML='<p class="muted">Saved into <code>'+
              esc(curDir?'/'+curDir:'the server root')+'</code>, keeping the name from the link. '+
              'The server fetches it, so the file never passes through this browser.</p>'+
              '<label class="f">Link</label>'+
              '<input id="ffurl" placeholder="https://… link to a jar, pack or config">'+
              '<div class="row2"><button class="btn go" id="ffgo">Fetch</button></div>'+
              '<div class="msg" id="ffmsg"></div>';
            const inp=$('ffurl'); inp.focus();
            const go=async()=>{
              const msg=$('ffmsg'), url=inp.value.trim();
              if(!url){ msg.className='msg err'; msg.textContent='Paste a link first.'; return; }
              msg.className='msg'; msg.textContent='Fetching…';
              const r=await jpost('/api/fetch',{url:url,dest:curDir+'/'});
              if(r.status===200){ close(); loadDir(curDir);
                fmsg(r.body.message||'Fetched.','ok'); }
              else { msg.className='msg err';
                msg.textContent=r.body.error||r.body.message||'fetch failed'; }
            };
            inp.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); go(); } };
            $('ffgo').onclick=go;
          });
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
        """;

    private static final String PART2 = """
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
          row.style.alignItems='center'; row.style.gap='10px';
          row.appendChild(avatar(p.name,p.uuid,'lg'));
          const left=document.createElement('span'); left.className='k'; left.style.whiteSpace='normal';
          // Real name first and always: an admin screen that showed only the
          // mask would be the one place the mask was not supposed to work.
          left.innerHTML='<b style="color:var(--ink)">'+esc(p.name)+'</b>'+
            (p.mask?' <span class="muted">appears to players as</span> '+
              '<span style="color:var(--brand)">'+esc(p.mask)+'</span>':'')+
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
                               death:'#e05a5a', attack:'#ff8a65', hurt:'#d98b6a',
                               join:'#57c957', leave:'#8b9096', respawn:'#8fd98f',
                               item:'#d3c26a', interact:'#9c8ce0', use:'#7f8a99',
                               place:'#66c2a5', 'break':'#e8a33d', afk:'#6f7a89' };
        function activityPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">What players have been doing. '+
            'Rows are deleted once they pass the retention window.</p>'+
            '<section><div class="bartitle"><h2>Everyone, over time</h2>'+
            '<span class="spacer"></span>'+
            '<span class="muted num" id="t-at"></span></div>'+
            '<div class="maplayout" id="t-layout">'+
              '<div>'+
                '<div id="t-map"><div class="note">loading…</div></div>'+
                '<div class="timeline" id="t-line"></div>'+
                '<div class="tlbar">'+
                  '<button class="btn go" id="t-play">Play</button>'+
                  '<span class="speed" id="t-speed"></span>'+
                  '<button class="btn" id="t-skip">Skip quiet time</button>'+
                  '<button class="btn" id="t-zoomout">Whole period</button>'+
                  '<span class="spacer"></span>'+
                  '<span class="muted" id="t-dims"></span>'+
                '</div>'+
                '<div class="legend" id="t-legend"></div>'+
              '</div>'+
              '<aside class="mapside" id="t-side"></aside>'+
            '</div></section>'+
            '<div id="a-admins" class="note" style="margin:12px 0"></div>'+
            '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px">'+
            '<input id="a-filter" placeholder="filter by player, action, detail or place" '+
            'style="flex:1;min-width:200px">'+
            '<button class="btn" id="a-refresh">Refresh</button>'+
            '<button class="btn danger" id="a-clear">Clear log</button></div>'+
            '<div id="a-meta" class="muted" style="margin-bottom:8px"></div>'+
            '<div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">'+
            '<span class="muted">One player’s path</span>'+
            '<select id="a-who" style="min-width:170px;width:auto"></select>'+
            '<span class="muted" id="a-dims"></span></div>'+
            '<div id="a-map"></div>'+
            '<div class="act" id="a-rows" style="margin-top:12px"><div class="note">loading…</div></div>'+
            '<div class="msg" id="a-msg"></div>';
          setTimeout(()=>{
            loadActivity(); loadTrackList(); loadAll();
            $('a-refresh').onclick=()=>{ loadActivity(); loadAll(); loadTrack($('a-who').value); };
            $('t-play').onclick=togglePlay;
            $('t-skip').onclick=()=>{ skipGaps=!skipGaps; paintAll(); };
            $('t-zoomout').onclick=()=>{ win.set=false; paintAll(); };
            $('a-clear').onclick=clearActivity;
            // Filtering is client-side over the rows already fetched, so
            // typing here asks the server for nothing.
            $('a-filter').oninput=paintActivity;
            $('a-who').onchange=()=>loadTrack($('a-who').value);
          },0);
          return wrap;
        }

        // ---- everyone, on one clock ----
        // The per-player map answers "where has this person been". This answers
        // the question you actually start with — "what happened here, and who
        // was around" — which needs everyone on the same timeline.
        let allData=null, allDim='', playTimer=null;

        // Where the map is looking, in world blocks rather than pixels: a
        // centre and how many blocks fit across it. Zooming and panning move
        // these, so the framing survives a repaint, a playback frame and a
        // change of dimension.
        let view={cx:0, cz:0, span:0, set:false};

        // Which slice of the period the timeline shows. Unset means all of it.
        let win={from:0, to:0, set:false};

        let cursorAt=0, cursorSet=false;
        let playSpeed=1, skipGaps=true;
        let focusPlayer='';
        let showOverlays=true;

        // How long the whole visible window takes to play at 1x.
        const PLAY_MS=20000;
        const FRAME_MS=50;

        // Nothing happening for this long is a gap worth marking and worth
        // skipping — shorter than this is just a quiet minute.
        const GAP_MS=90*1000;

        // How far back from the cursor a marker still counts as "just now".
        // A fraction of the period, so a busy hour and a quiet day both read.
        const MARKER_WINDOW=0.12;

        // Markers were sized for a map you looked at whole. Now that it zooms,
        // they are the thing you are looking for, so they are drawn larger.
        const MARK_SCALE=1.7;

        // Stable per-player colour: the same person is the same colour every
        // time the map is drawn, without keeping a palette in sync with a
        // player list that changes.
        function playerColor(name){
          let h=0;
          for(let i=0;i<name.length;i++) h=(h*31+name.charCodeAt(i))>>>0;
          return 'hsl('+(h%360)+' 62% 62%)';
        }

        // Pictures of the ground, so the map has a world under it rather than
        // a grid. Listed once; the browser picks which one matches the cursor.
        let shots=[], shotEvery=0;

        async function loadAll(){
          const box=$('t-map'); if(!box) return;
          const r=await jget('/api/track?all=1');
          if(r.status!==200){ box.innerHTML='<div class="note">unavailable</div>'; return; }
          allData=r.body; allDim='';
          showAdmins(r.body.admins);
          const m=await jget('/api/map');
          shots=(m.status===200 && m.body.shots)?m.body.shots:[];
          shotEvery=(m.body&&m.body.every)||0;
          cursorSet=false; win.set=false; view.set=false;
          paintAll();
        }

        // The newest picture taken at or before the cursor — what the ground
        // looked like then. Falls back to the earliest there is, because an
        // approximately-right world beats an empty grid.
        function shotFor(dim,at){
          let best=null, earliest=null;
          for(const s of shots){
            if(s.dim!==dim) continue;
            if(!earliest || s.at<earliest.at) earliest=s;
            if(s.at<=at && (!best || s.at>best.at)) best=s;
          }
          return best||earliest;
        }

        /**
         * Stretches of the period with nobody about.
         *
         * <p>Worked out from the record itself rather than from join and leave
         * rows, which are only present if someone was being recorded at the
         * time. Every moment anything was written down, sorted; a run between
         * two of them longer than GAP_MS is time nobody was playing.
         */
        function quietGaps(){
          if(!allData) return [];
          const at=[];
          for(const n of Object.keys(allData.tracks||{}))
            for(const p of allData.tracks[n]) at.push(p.at);
          for(const a of (allData.actions||[])) at.push(a.at);
          if(at.length<2) return [];
          at.sort((x,y)=>x-y);
          const gaps=[];
          for(let i=1;i<at.length;i++){
            if(at[i]-at[i-1] > GAP_MS) gaps.push({from:at[i-1], to:at[i]});
          }
          return gaps;
        }
        /** The gap the given moment falls inside, or null. */
        function gapAt(t,gaps){
          for(const g of gaps) if(t>g.from && t<g.to) return g;
          return null;
        }

        // ---- what each action looks like on the map ----
        // Drawn rather than fetched: the panel has to work on a server with no
        // way out to the internet, and an icon set would be another thing to
        // ship and license. A shape per action, all built from two primitives.
        /**
         * One action's shape, centred on (x, y).
         *
         * <p>The scale is applied as a transform about that point rather than
         * to each shape's own numbers: half of them ignored it before, so
         * asking for larger markers made three of fourteen larger and left the
         * rest where they were.
         */
        function marker(action,x,y,fill,scale){
          const r=(scale||1);
          const body=markerShape(action,x,y,fill);
          if(r===1) return body;
          return '<g transform="translate('+x+' '+y+') scale('+r.toFixed(3)+') translate('+
            (-x)+' '+(-y)+')">'+body+'</g>';
        }
        function markerShape(action,x,y,fill){
          const c=fill, r=1;
          const sq=(k,f)=>'<rect x="'+(x-k)+'" y="'+(y-k)+'" width="'+(2*k)+'" height="'+(2*k)+
            '" fill="'+(f?c:'none')+'" stroke="'+(f?'#0b0d11':c)+'" stroke-width="1.6" rx="1"/>';
          const li=(x1,y1,x2,y2,w)=>'<line x1="'+(x+x1)+'" y1="'+(y+y1)+'" x2="'+(x+x2)+
            '" y2="'+(y+y2)+'" stroke="'+c+'" stroke-width="'+(w||1.8)+'" stroke-linecap="round"/>';
          const poly=(pts,f)=>'<polygon points="'+pts.map(q=>(x+q[0])+','+(y+q[1])).join(' ')+
            '" fill="'+(f?c:'none')+'" stroke="'+(f?'#0b0d11':c)+'" stroke-width="1.4"/>';
          const dot=(k)=>'<circle cx="'+x+'" cy="'+y+'" r="'+k+'" fill="'+c+
            '" stroke="#0b0d11" stroke-width="1.4"/>';
          switch(action){
            // A block put down is a solid square; one taken away is the hole
            // it left, with the break through it.
            case 'place':     return sq(4.5*r,true);
            case 'break':     return sq(4.5*r,false)+li(-3,-3,3,3,1.5)+li(-3,3,3,-3,1.5);
            // Crossed swords.
            case 'attack':    return li(-5,-5,5,5,2.2)+li(-5,5,5,-5,2.2);
            // A hit taken: a burst.
            case 'hurt':      return li(0,-6,0,6,1.8)+li(-6,0,6,0,1.8)+li(-4,-4,4,4,1.4)+
                                     li(-4,4,4,-4,1.4);
            case 'death':     return dot(5)+'<path d="M'+(x-2.4)+' '+(y-1.4)+'l4.8 0M'+
                                     (x-2.4)+' '+(y+1.8)+'l4.8 0" stroke="#0b0d11" '+
                                     'stroke-width="1.4"/>';
            // A speech bubble, tail down-left.
            case 'chat':      return '<path d="M'+(x-6)+' '+(y-5)+'h12a2 2 0 0 1 2 2v5a2 2 0 0 1'+
                                     ' -2 2h-6l-4 3v-3h-2a2 2 0 0 1 -2 -2v-5a2 2 0 0 1 2 -2z" '+
                                     'fill="'+c+'" stroke="#0b0d11" stroke-width="1.2"/>';
            // A prompt.
            case 'command':   return li(-4,-4,1,0,2)+li(1,0,-4,4,2)+li(2,4,6,4,2);
            // A chest: lid line and latch.
            case 'container': return sq(5*r,true)+'<path d="M'+(x-5)+' '+(y-1)+'h10" '+
                                     'stroke="#0b0d11" stroke-width="1.4"/>'+
                                     '<rect x="'+(x-1.2)+'" y="'+(y-2.2)+'" width="2.4" '+
                                     'height="3.4" fill="#0b0d11"/>';
            case 'join':      return poly([[-4,-5],[4,0],[-4,5]],true);
            case 'leave':     return poly([[4,-5],[-4,0],[4,5]],true);
            case 'respawn':   return '<circle cx="'+x+'" cy="'+y+'" r="5" fill="none" stroke="'+c+
                                     '" stroke-width="2"/>'+li(-2.5,0,2.5,0,1.6)+li(0,-2.5,0,2.5,1.6);
            case 'item':      return poly([[0,-5.5],[5.5,0],[0,5.5],[-5.5,0]],true);
            case 'interact':  return dot(3.4)+'<circle cx="'+x+'" cy="'+y+'" r="6" fill="none" '+
                                     'stroke="'+c+'" stroke-width="1.4"/>';
            // Stopped: a pause, inside the ring that means "still here".
            case 'afk':       return '<circle cx="'+x+'" cy="'+y+'" r="6" fill="none" stroke="'+c+
                                     '" stroke-width="1.5" stroke-dasharray="2.6 2.2"/>'+
                                     li(-1.8,-3,-1.8,3,2)+li(1.8,-3,1.8,3,2);
            default:          return dot(4);
          }
        }


        function stopPlay(){
          if(playTimer){ clearInterval(playTimer); playTimer=null; }
          const b=$('t-play'); if(b){ b.textContent='Play'; b.className='btn go'; }
        }
        function togglePlay(){
          if(playTimer){ stopPlay(); return; }
          const b=$('t-play'); if(!b) return;
          b.textContent='Pause'; b.className='btn on';
          const gaps=quietGaps();
          playTimer=setInterval(()=>{
            if(!allData){ stopPlay(); return; }
            const to=allData.to||0;
            const span=Math.max(1,(win.set?win.to-win.from:(to-(allData.from||0))));
            cursorAt += (span/PLAY_MS)*FRAME_MS*playSpeed;
            cursorSet=true;
            // Nothing happened here and nobody is watching an empty map for a
            // real-time hour. Step over it, and let the timeline show why.
            if(skipGaps){
              const g=gapAt(cursorAt,gaps);
              if(g) cursorAt=g.to;
            }
            if(cursorAt>=to){ cursorAt=to; paintAll(); stopPlay(); return; }
            // Keep the cursor in view: playing past the edge of the window
            // would leave you watching a strip the cursor has left.
            if(win.set && cursorAt>win.to){
              const w=win.to-win.from;
              win.from=cursorAt-w*0.15; win.to=win.from+w;
            }
            paintAll();
          },FRAME_MS);
        }

        // ---- painting ----
        // Coalesced onto a frame: dragging the map fires pointermove far more
        // often than the screen refreshes, and rebuilding for each one is work
        // nobody sees.
        // Where world coordinates land on screen, as of the last paint. The
        // gesture handlers live on the containers, which outlive a repaint,
        // so they cannot close over the numbers — they read them from here.
        let proj={W:1000,H:600,span:400,anchorX:500};
        function worldX(px){ return view.cx+((px-proj.anchorX)/proj.W)*proj.span; }
        function worldZ(py){ return view.cz+((py-proj.H/2)/proj.W)*proj.span; }

        let paintQueued=false;
        function schedulePaint(){
          if(paintQueued) return;
          paintQueued=true;
          requestAnimationFrame(()=>{ paintQueued=false; paintAll(); });
        }

        function paintAll(){
          const box=$('t-map'); if(!box || !allData) return;
          const tracks=allData.tracks||{}, acts=allData.actions||[];
          const ids=allData.ids||{}, online=allData.online||[];
          const names=Object.keys(tracks);
          if(!names.length && !acts.length){
            box.innerHTML='<div class="note">Nothing recorded yet'+
              ((allData.trackSeconds===0)?' — activity-track-seconds is 0, so paths are off.'
                                         :'. It fills in as people play.')+'</div>';
            const line=$('t-line'); if(line) line.innerHTML='';
            const side=$('t-side'); if(side) side.innerHTML='';
            $('t-at').textContent=''; $('t-dims').textContent=''; return;
          }

          // One dimension at a time: overworld and nether coordinates share
          // numbers but not places, and drawing them together is a lie.
          const all=[].concat(...names.map(n=>tracks[n])).concat(acts);
          const dims=[...new Set(all.map(p=>p.dim).filter(Boolean))];
          if(!allDim || !dims.includes(allDim)) allDim=dims[0]||'';
          $('t-dims').innerHTML = dims.length>1
            ? dims.map(d=>'<button class="btn'+(d===allDim?' on':'')+'" data-tdim="'+esc(d)+'" '+
                'style="padding:3px 9px;font-size:12px;margin-left:6px">'+esc(d)+'</button>').join('')
            : esc(allDim);

          const from=allData.from||0, to=allData.to||from+1;
          if(!cursorSet){ cursorAt=to; cursorSet=true; }
          cursorAt=Math.max(from,Math.min(to,cursorAt));
          const cursor=cursorAt;
          const windowMs=Math.max(1,(to-from)*MARKER_WINDOW);
          $('t-at').textContent=fmtAgo(cursor)+(cursor>=to-1000?' (now)':'');

          const inDim=p=>p.dim===allDim;
          const mine=a=>!focusPlayer || a.player===focusPlayer;
          // Everything that had happened by the cursor, not just the last
          // moment of it. A narrow window looks tidy and is useless: scrub to
          // a quiet minute and the map goes blank, which says nothing about
          // where anything happened. Age is carried by fading instead.
          const shownActs=acts.filter(a=>inDim(a) && a.at<=cursor && mine(a));
          const shownNames=names.filter(n=>!focusPlayer || n===focusPlayer);

          const whole=[].concat(...shownNames.map(n=>tracks[n].filter(inDim)))
                        .concat(acts.filter(a=>inDim(a)&&mine(a)));
          const shot=shotFor(allDim,cursor);
          if(!whole.length && !shot){
            box.innerHTML='<div class="note">Nothing in '+esc(allDim)+
              (focusPlayer?' for '+esc(focusPlayer):'')+'.</div>';
            wireDims(); paintTimeline(); paintSide([]); return;
          }

          // The framing survives panning and zooming; it is only worked out
          // from the data the first time, or when Whole period is pressed.
          if(!view.set){
            if(shot){
              view.cx=shot.minX+shot.span/2;
              view.cz=shot.minZ+shot.span/2;
              view.span=shot.span;
            } else {
              const xs=whole.map(p=>p.x), zs=whole.map(p=>p.z);
              const minX=Math.min(...xs), maxX=Math.max(...xs);
              const minZ=Math.min(...zs), maxZ=Math.max(...zs);
              view.cx=(minX+maxX)/2; view.cz=(minZ+maxZ)/2;
              view.span=Math.max(maxX-minX,maxZ-minZ,32)*1.12;
            }
            view.set=true;
          }

          const W=1000, H=Math.round(W*0.60);
          const side=$('t-side'), layout=$('t-layout');
          const wide=layout ? layout.clientWidth>=900 : false;
          const sidebar=wide && showOverlays;
          if(layout) layout.className='maplayout'+(sidebar?' side':'');
          if(side) side.style.display=sidebar?'':'none';
          // With a panel beside the map, dead centre is not the middle of what
          // you can see. Nudged left so the interesting half is not under it.
          const anchorX=sidebar?W*0.46:W/2;

          const span=Math.max(8,view.span);
          proj={W:W,H:H,span:span,anchorX:anchorX};
          const sx=v=>((v-view.cx)/span)*W+anchorX;
          const sz=v=>((v-view.cz)/span)*W+H/2;

          const grid=[];
          for(let g=0;g<=4;g++){
            grid.push('<line x1="'+(W/4)*g+'" y1="0" x2="'+(W/4)*g+'" y2="'+H+'" stroke="#1b1f27"/>');
            grid.push('<line x1="0" y1="'+(H/4)*g+'" x2="'+W+'" y2="'+(H/4)*g+'" stroke="#1b1f27"/>');
          }

          // The ground as it was at the cursor. Nearest-neighbour scaling, so
          // it reads as blocks rather than as a blur.
          const groundImage=shot
            // Addressed by the picture's own timestamp, not the cursor's:
            // during playback the cursor changes every frame, and that URL
            // would be a fresh request each time instead of a cache hit.
            ? '<image href="/api/map?at='+shot.at+'&dim='+encodeURIComponent(allDim)+
              '" x="'+sx(shot.minX).toFixed(1)+'" y="'+sz(shot.minZ).toFixed(1)+
              '" width="'+(sx(shot.minX+shot.span)-sx(shot.minX)).toFixed(1)+
              '" height="'+(sz(shot.minZ+shot.span)-sz(shot.minZ)).toFixed(1)+
              '" preserveAspectRatio="none" style="image-rendering:pixelated" opacity=".95"/>'
            : '';

          const heads=[];
          const lines=shownNames.map(n=>{
            const c=playerColor(n);
            const full=tracks[n].filter(inDim);
            if(!full.length) return '';
            const upto=full.filter(p=>p.at<=cursor);
            const d=pts=>pts.map((p,i)=>(i?'L':'M')+sx(p.x).toFixed(1)+' '+
              sz(p.z).toFixed(1)).join(' ');
            // The whole path faintly, so you can see where to scrub to; the
            // travelled part solid on top of it.
            let out='<path d="'+d(full)+'" fill="none" stroke="'+c+'" stroke-width="1.8" '+
              'stroke-opacity=".16" stroke-linejoin="round" stroke-linecap="round"/>';
            if(upto.length){
              out+='<path d="'+d(upto)+'" fill="none" stroke="'+c+'" stroke-width="2.8" '+
                'stroke-opacity=".72" stroke-linejoin="round" stroke-linecap="round"/>';
              // Where they were at the cursor, drawn as their own face. The
              // ring is under it and stays visible if the face never loads.
              const last=upto[upto.length-1];
              const hx=sx(last.x), hy=sz(last.z), R=13;
              let head='<circle cx="'+hx.toFixed(1)+'" cy="'+hy.toFixed(1)+'" r="'+R+
                '" fill="'+c+'" stroke="#0b0d11" stroke-width="2"/>';
              if(headsOn && ids[n]){
                head+='<image href="/api/head?uuid='+encodeURIComponent(ids[n])+
                  '&name='+encodeURIComponent(n)+'" x="'+(hx-R+3).toFixed(1)+
                  '" y="'+(hy-R+3).toFixed(1)+'" width="'+(R*2-6)+'" height="'+(R*2-6)+
                  '" style="image-rendering:pixelated" clip-path="circle(50%)"/>';
              }
              heads.push('<g class="thead" data-who="'+esc(n)+'" style="cursor:pointer">'+
                head+'</g>');
            }
            return out;
          }).join('');

          const dots=shownActs.map((a,i)=>{
            // Recent is bright, older stays visible. Never to zero: a mark you
            // cannot see is the same as one that is not drawn.
            const age=Math.min(1,(cursor-a.at)/windowMs);
            // Recent stands out, but the floor is high: over a long period
            // almost everything is "old", and fading those to nothing would
            // empty the map of the very marks it exists to show.
            const fade=Math.max(0.55,0.98-age*0.43);
            return '<g class="tmk" data-i="'+i+'" opacity="'+fade.toFixed(2)+'">'+
              marker(a.action,+sx(a.x).toFixed(1),+sz(a.z).toFixed(1),
                ACTION_COLOR[a.action]||'#9aa3ae',MARK_SCALE*(age>0.99?0.75:1))+'</g>';
          }).join('');

          box.innerHTML='<div class="mapwrap">'+
            '<svg id="t-svg" viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="xMidYMid meet" '+
            'role="img" aria-label="Where everyone was and what they did">'+
            grid.join('')+groundImage+lines+dots+heads.join('')+'</svg>'+
            '<div class="maptip" id="t-tip"></div>'+
            (showOverlays?'<div class="onlinebar" id="t-online"></div>':'')+
            '<div class="mapbtns">'+
              '<button id="t-in" title="Zoom in">+</button>'+
              '<button id="t-out" title="Zoom out">−</button>'+
              '<button id="t-home" title="Fit everything in view">⌂</button>'+
            '</div>'+
            '<div class="mapbtns bottom">'+
              '<button id="t-cog" title="'+(showOverlays?'Hide the overlays':'Show the overlays')+
              '">'+ICON.cog+'</button>'+
            '</div></div>';

          paintOnline(online,ids);
          paintLegend(shownNames,shownActs,span,shot);
          paintTimeline();
          paintSide(acts.filter(a=>mine(a)));
          wireMapGestures();
          wireMapButtons();
          wireMarkers(box,shownActs,W,H);
          wireDims();
        }

        /** Who is on right now, greyed if they have stopped moving. */
        function paintOnline(online,ids){
          const bar=$('t-online'); if(!bar) return;
          if(!online.length){
            bar.innerHTML='<span class="who" style="cursor:default">nobody online</span>';
            return;
          }
          bar.innerHTML='';
          for(const w of online){
            const el=document.createElement('span');
            el.className='who'+(w.afk?' afk':'')+(focusPlayer===w.name?' on':'');
            el.title=(w.afk?'Away — no movement since '+fmtAgo(w.stillSince)
                           :'Playing')+' · '+w.dim+' '+w.x+','+w.y+','+w.z+
                     '\\n(click to show only this player)';
            el.appendChild(avatar(w.name,w.uuid,'sm'));
            const t=document.createElement('span');
            t.textContent=(w.mask?w.mask:w.name)+(w.afk?' · afk':'');
            el.appendChild(t);
            el.onclick=()=>{ focusPlayer=focusPlayer===w.name?'':w.name; paintAll(); };
            bar.appendChild(el);
          }
        }

        function paintLegend(shownNames,shownActs,span,shot){
          const used=[...new Set(shownActs.map(a=>a.action))];
          const key=used.map(u=>'<span><svg width="16" height="16" viewBox="-9 -9 18 18" '+
            'style="vertical-align:-3px">'+marker(u,0,0,ACTION_COLOR[u]||'#9aa3ae',1.1)+
            '</svg> '+esc(u)+'</span>').join('');
          $('t-legend').innerHTML=
            shownNames.map(n=>'<span class="pill-who" data-who="'+esc(n)+'" '+
              'style="cursor:pointer"><i style="background:'+playerColor(n)+'"></i>'+
              esc(n)+'</span>').join('')+key+
            '<span class="muted">'+shownNames.length+' player(s) · '+shownActs.length+
            ' action(s) by then · '+Math.round(span)+' blocks across'+
            (focusPlayer?' · showing only '+esc(focusPlayer):'')+
            (shot?' · ground from '+fmtAgo(shot.at)
                 :' · no picture of the ground yet (map-snapshot-seconds)')+
            '</span>';
          $('t-legend').querySelectorAll('.pill-who').forEach(el=>{
            el.onclick=()=>{ const n=el.getAttribute('data-who');
              focusPlayer=focusPlayer===n?'':n; paintAll(); };
          });
        }

        /** The list beside the map: what happened, newest at the cursor first. */
        function paintSide(acts){
          const side=$('t-side'); if(!side) return;
          const near=acts.filter(a=>a.at<=cursorAt).sort((a,b)=>b.at-a.at).slice(0,120);
          side.innerHTML='<h3>'+(focusPlayer?esc(focusPlayer):'Everyone')+
            ' · '+near.length+' shown</h3>';
          if(!near.length){
            side.insertAdjacentHTML('beforeend',
              '<div class="note">Nothing before this point.</div>');
            return;
          }
          for(const a of near){
            const row=document.createElement('div');
            row.className='sideact'+(a.action==='chat'?' say':'');
            row.appendChild(avatar(a.player,(allData.ids||{})[a.player],'sm'));
            const body=document.createElement('div');
            body.innerHTML='<div class="l1"><span class="nm">'+esc(a.mask||a.player)+'</span>'+
              '<span style="color:'+(ACTION_COLOR[a.action]||'#9aa3ae')+'">'+esc(a.action)+
              (a.count>1?' ×'+a.count:'')+'</span>'+
              '<span class="tm">'+esc(fmtAgo(a.at).replace(' ago',''))+'</span></div>'+
              (a.detail?'<div class="dt">'+esc(a.detail)+'</div>':'');
            row.appendChild(body);
            // Clicking a row takes the map to it: the moment and the place.
            row.onclick=()=>{ cursorAt=a.at; cursorSet=true; allDim=a.dim;
              view.cx=a.x; view.cz=a.z; view.set=true; stopPlay(); paintAll(); };
            side.appendChild(row);
          }
        }

        /**
         * The timeline: an overview of the whole period with the visible slice
         * marked, and under it that slice drawn large.
         *
         * <p>Two strips rather than one because they answer different
         * questions — where am I in the day, and what is happening right here —
         * and a single bar zoomed into ten minutes cannot answer the first.
         */
        function paintTimeline(){
          const host=$('t-line'); if(!host || !allData) return;
          const from=allData.from||0, to=allData.to||from+1;
          if(!win.set){ win.from=from; win.to=to; }
          // Never let the window escape the period or collapse to nothing.
          const minSpan=Math.max(2000,(to-from)/2000);
          if(win.to-win.from<minSpan) win.to=win.from+minSpan;
          if(win.from<from){ win.to+=from-win.from; win.from=from; }
          if(win.to>to){ win.from-=win.to-to; win.to=to; }
          if(win.from<from) win.from=from;

          const W=1000, OV=14, GAP=8, MAIN=54, H=OV+GAP+MAIN;
          const ovx=t=>((t-from)/Math.max(1,to-from))*W;
          const mx=t=>((t-win.from)/Math.max(1,win.to-win.from))*W;
          const gaps=quietGaps();

          let sv='<rect x="0" y="0" width="'+W+'" height="'+OV+'" fill="#151922"/>';
          for(const g of gaps){
            sv+='<rect x="'+ovx(g.from).toFixed(1)+'" y="0" width="'+
              Math.max(1,ovx(g.to)-ovx(g.from)).toFixed(1)+'" height="'+OV+
              '" fill="#0b0d11"/>';
          }
          sv+='<rect class="ovwin" x="'+ovx(win.from).toFixed(1)+'" y="0" width="'+
            Math.max(3,ovx(win.to)-ovx(win.from)).toFixed(1)+'" height="'+OV+
            '" fill="#ffab33" fill-opacity=".26" stroke="#ffab33" stroke-width="1"/>';

          const y0=OV+GAP;
          sv+='<rect x="0" y="'+y0+'" width="'+W+'" height="'+MAIN+'" fill="#151922"/>';
          // Quiet time, marked rather than hidden: the map is empty there for
          // a reason, and hiding it would make the clock lie about how long
          // the day was.
          for(const g of gaps){
            const a=Math.max(0,mx(g.from)), b=Math.min(W,mx(g.to));
            if(b<=a) continue;
            sv+='<rect x="'+a.toFixed(1)+'" y="'+y0+'" width="'+(b-a).toFixed(1)+
              '" height="'+MAIN+'" fill="url(#quiet)"/>';
            if(b-a>54){
              sv+='<text x="'+((a+b)/2).toFixed(1)+'" y="'+(y0+MAIN/2+4)+
                '" text-anchor="middle" fill="#5b6472" font-size="11">nobody on</text>';
            }
          }
          // One tick per action, coloured by what it was.
          for(const a of (allData.actions||[])){
            if(a.dim!==allDim) continue;
            if(focusPlayer && a.player!==focusPlayer) continue;
            const x=mx(a.at);
            if(x<-2||x>W+2) continue;
            sv+='<line x1="'+x.toFixed(1)+'" y1="'+(y0+MAIN-20)+'" x2="'+x.toFixed(1)+
              '" y2="'+(y0+MAIN-4)+'" stroke="'+(ACTION_COLOR[a.action]||'#9aa3ae')+
              '" stroke-width="1.4" stroke-opacity=".8"/>';
          }
          // Hour marks, so the strip is a clock and not just a bar.
          const spanMs=win.to-win.from;
          const step=spanMs>6*3600e3?3600e3:spanMs>3600e3?600e3:spanMs>600e3?60e3:10e3;
          for(let t=Math.ceil(win.from/step)*step;t<=win.to;t+=step){
            const x=mx(t);
            sv+='<line x1="'+x.toFixed(1)+'" y1="'+y0+'" x2="'+x.toFixed(1)+'" y2="'+
              (y0+8)+'" stroke="#39414e"/>';
          }
          const cx=mx(cursorAt);
          if(cx>=-2 && cx<=W+2){
            sv+='<line x1="'+cx.toFixed(1)+'" y1="'+y0+'" x2="'+cx.toFixed(1)+'" y2="'+
              (y0+MAIN)+'" stroke="#ffab33" stroke-width="2"/>'+
              '<circle cx="'+cx.toFixed(1)+'" cy="'+(y0+6)+'" r="5" fill="#ffab33"/>';
          }

          host.innerHTML='<svg id="t-tsvg" viewBox="0 0 '+W+' '+H+
            '" preserveAspectRatio="none" role="img" aria-label="Timeline">'+
            '<defs><pattern id="quiet" width="8" height="8" patternUnits="userSpaceOnUse" '+
            'patternTransform="rotate(45)"><rect width="8" height="8" fill="#101319"/>'+
            '<line x1="0" y1="0" x2="0" y2="8" stroke="#1b2029" stroke-width="4"/></pattern>'+
            '</defs>'+sv+'</svg>';
          tl={W:W,OV:OV,GAP:GAP,MAIN:MAIN,from:from,to:to};
          wireTimeline();
          paintSpeed();
          const skip=$('t-skip');
          if(skip){ skip.className='btn'+(skipGaps?' on':'');
            skip.title=skipGaps?'Playback jumps over time nobody was on'
                               :'Playback runs through quiet time in real proportion'; }
        }

        function paintSpeed(){
          const box=$('t-speed'); if(!box) return;
          box.innerHTML='';
          for(const s of [0.25,0.5,1,2,4,8]){
            const b=document.createElement('button');
            b.textContent=(s<1?s:s+'')+'×';
            if(s===playSpeed) b.className='on';
            b.title='Play at '+s+' times speed';
            b.onclick=()=>{ playSpeed=s; paintSpeed(); };
            box.appendChild(b);
          }
        }

        /**
         * Keeps the pointer with this element for the rest of the drag.
         *
         * <p>Guarded: capture throws for a pointer the browser does not
         * consider active, and losing the drag entirely over a nicety that
         * only stops it from being interrupted would be a poor trade.
         */
        function capture(el,e){
          try { el.setPointerCapture(e.pointerId); } catch(err) { /* drag still works */ }
        }

        // The timeline's geometry as of the last paint, for the same reason.
        let tl={W:1000,OV:14,GAP:8,MAIN:54,from:0,to:1};

        /**
         * Scrubbing, zooming and panning the timeline.
         *
         * <p>Bound to the container, once, rather than to the SVG inside it.
         * The SVG is rebuilt on every repaint — including every frame of
         * playback — so a listener on it dies the moment the first drag causes
         * a paint, which is to say immediately.
         */
        function wireTimeline(){
          const host=$('t-line');
          if(!host || host.almWired) return;
          host.almWired=true;
          const at=e=>{
            const svg=host.querySelector('svg');
            const r=(svg||host).getBoundingClientRect();
            return {x:((e.clientX-r.left)/r.width)*tl.W,
                    y:((e.clientY-r.top)/r.height)*(tl.OV+tl.GAP+tl.MAIN)};
          };
          const setCursor=x=>{
            cursorAt=win.from+(x/tl.W)*(win.to-win.from);
            cursorSet=true; stopPlay(); schedulePaint();
          };
          const centreWindow=x=>{
            const w=win.to-win.from;
            const t=tl.from+(x/tl.W)*(tl.to-tl.from);
            win.from=t-w/2; win.to=t+w/2; win.set=true;
          };
          let mode='';
          host.addEventListener('pointerdown',e=>{
            const p=at(e);
            capture(host,e);
            // The overview strip moves the window; the strip below it moves
            // the cursor.
            if(p.y<=tl.OV){ centreWindow(p.x); mode='window'; }
            else { mode='scrub'; setCursor(p.x); }
            schedulePaint();
          });
          host.addEventListener('pointermove',e=>{
            if(!mode) return;
            const p=at(e);
            if(mode==='scrub') setCursor(p.x);
            else { centreWindow(p.x); schedulePaint(); }
          });
          const end=()=>{ mode=''; };
          host.addEventListener('pointerup',end);
          host.addEventListener('pointercancel',end);
          host.addEventListener('wheel',e=>{
            e.preventDefault();
            const p=at(e);
            // Zoom about the pointer, so the moment under it stays under it.
            const focus=win.from+(p.x/tl.W)*(win.to-win.from);
            const k=e.deltaY>0?1.25:0.8;
            win.from=focus-(focus-win.from)*k;
            win.to=focus+(win.to-focus)*k;
            win.set=true;
            schedulePaint();
          },{passive:false});
        }

        /**
         * Panning and point-zooming the map itself.
         *
         * <p>On the container, once, for the same reason the timeline is:
         * the SVG under it is replaced on every repaint.
         */
        function wireMapGestures(){
          const host=$('t-map');
          if(!host || host.almWired) return;
          host.almWired=true;
          const at=e=>{
            const svg=host.querySelector('svg');
            if(!svg) return null;
            const r=svg.getBoundingClientRect();
            // preserveAspectRatio="meet" letterboxes; undo that, or the point
            // under the cursor is not the point that stays put.
            const scale=Math.min(r.width/proj.W,r.height/proj.H);
            if(!scale) return null;
            const ox=(r.width-proj.W*scale)/2, oy=(r.height-proj.H*scale)/2;
            return {x:(e.clientX-r.left-ox)/scale, y:(e.clientY-r.top-oy)/scale};
          };
          let dragging=false, lastX=0, lastY=0;
          host.addEventListener('pointerdown',e=>{
            if(e.target.closest('.tmk')||e.target.closest('.thead')) return;
            if(e.target.closest('.mapbtns')||e.target.closest('.onlinebar')) return;
            const p=at(e); if(!p) return;
            dragging=true; lastX=p.x; lastY=p.y;
            const svg=host.querySelector('svg');
            if(svg) svg.classList.add('grabbing');
            capture(host,e);
          });
          host.addEventListener('pointermove',e=>{
            if(!dragging) return;
            const p=at(e); if(!p) return;
            view.cx-=((p.x-lastX)/proj.W)*proj.span;
            view.cz-=((p.y-lastY)/proj.W)*proj.span;
            view.set=true;
            lastX=p.x; lastY=p.y;
            schedulePaint();
          });
          const stop=()=>{
            dragging=false;
            const svg=host.querySelector('svg');
            if(svg) svg.classList.remove('grabbing');
          };
          host.addEventListener('pointerup',stop);
          host.addEventListener('pointercancel',stop);
          host.addEventListener('wheel',e=>{
            const p=at(e); if(!p) return;
            e.preventDefault();
            // Zoom about the pointer: the block under it stays under it.
            const bx=worldX(p.x), bz=worldZ(p.y);
            const k=e.deltaY>0?1.18:1/1.18;
            const next=Math.max(16,Math.min(20000,proj.span*k));
            const f=next/proj.span;
            view.cx=bx+(view.cx-bx)*f;
            view.cz=bz+(view.cz-bz)*f;
            view.span=next;
            view.set=true;
            schedulePaint();
          },{passive:false});
        }

        /** The controls drawn inside the map, which a repaint does replace. */
        function wireMapButtons(){
          const zoom=k=>{ view.span=Math.max(16,Math.min(20000,view.span*k));
            view.set=true; paintAll(); };
          const inb=$('t-in'), outb=$('t-out'), home=$('t-home'), cog=$('t-cog');
          if(inb) inb.onclick=()=>zoom(1/1.5);
          if(outb) outb.onclick=()=>zoom(1.5);
          if(home) home.onclick=()=>{ view.set=false; paintAll(); };
          if(cog) cog.onclick=()=>{ showOverlays=!showOverlays; paintAll(); };
          const svg=$('t-svg');
          if(svg) svg.querySelectorAll('.thead').forEach(el=>{
            el.onclick=()=>{ const n=el.getAttribute('data-who');
              focusPlayer=focusPlayer===n?'':n; paintAll(); };
          });
        }

        function wireMarkers(box,shownActs,W,H){
          const svg=$('t-svg'), tip=$('t-tip');
          if(!svg||!tip) return;
          box.querySelectorAll('.tmk').forEach(el=>{
            el.addEventListener('mouseenter',()=>{
              const a=shownActs[+el.getAttribute('data-i')];
              if(!a) return;
              tip.textContent=(a.mask?a.mask+' ('+a.player+')':a.player)+' · '+a.action+
                (a.count>1?' x'+a.count:'')+(a.detail?' · '+a.detail:'')+
                ' · '+a.x+','+a.y+','+a.z+' · '+fmtAgo(a.at);
              const r=svg.getBoundingClientRect(), b=box.getBoundingClientRect();
              const g=el.getBBox();
              tip.style.left=(r.left-b.left+(g.x+g.width/2)/W*r.width)+'px';
              tip.style.top=(r.top-b.top+g.y/H*r.height-26)+'px';
              tip.style.opacity='1';
            });
            el.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          });
        }

        function wireDims(){
          const host=$('t-dims'); if(!host) return;
          host.querySelectorAll('[data-tdim]').forEach(b=>
            b.onclick=()=>{ allDim=b.getAttribute('data-tdim'); paintAll(); });
        }

        // ---- who is recorded ----
        function showAdmins(p){
          const box=$('a-admins'); if(!box) return;
          if(!p){ box.textContent=''; return; }
          box.innerHTML=(p.includeAdmins
              ? '<span class="state warn">Admins included</span> Ops and trusted UUIDs are '+
                'being recorded alongside everyone else.'
              : '<span class="state good">Admins excluded</span> Anyone who could read this '+
                '— a trusted UUID, or any op — is not recorded.')+
            (p.temporary ? ' <span class="muted">Set for this run only; the saved setting is '+
              (p.configured?'on':'off')+'.</span>' : '')+
            '<div style="display:flex;gap:8px;margin-top:9px;flex-wrap:wrap">'+
            '<button class="btn" id="a-adm-save">'+(p.configured?'Stop recording admins':
              'Record admins')+' (saved)</button>'+
            '<button class="btn" id="a-adm-temp">'+(p.includeAdmins?'Stop recording admins':
              'Record admins')+' until restart</button>'+
            (p.temporary?'<button class="btn" id="a-adm-clear">Back to the setting</button>':'')+
            '</div>';
          $('a-adm-save').onclick=()=>setAdmins(!p.configured,false);
          $('a-adm-temp').onclick=()=>setAdmins(!p.includeAdmins,true);
          const c=$('a-adm-clear'); if(c) c.onclick=()=>setAdmins(null,true);
        }
        async function setAdmins(value,temporary){
          const r=await jpost('/api/activity',{action:'admins',value:value,temporary:temporary});
          if(r.status!==200){ alert(r.body.error||'failed'); return; }
          showAdmins(r.body);
          loadActivity();
        }

        // ---- the movement map ----
        let trackData=null, trackDim='';
        async function loadTrackList(){
          const sel=$('a-who'); if(!sel) return;
          const r=await jget('/api/track');
          const players=(r.status===200 && r.body.players)?r.body.players:{};
          const names=Object.keys(players);
          sel.innerHTML='<option value="">— pick a player —</option>'+
            names.map(n=>'<option value="'+esc(n)+'">'+esc(n)+' ('+players[n]+' points)</option>').join('');
          const box=$('a-map');
          if(!names.length && box){
            box.innerHTML='<div class="note">No movement recorded yet'+
              ((r.body&&r.body.trackSeconds===0)?' — activity-track-seconds is 0, so the map is off.'
                                                :'. It fills in as people play.')+'</div>';
          }
        }
        async function loadTrack(who){
          const box=$('a-map'); if(!box) return;
          if(!who){ box.innerHTML=''; trackData=null; return; }
          box.innerHTML='<div class="note">loading…</div>';
          const r=await jget('/api/track?player='+encodeURIComponent(who));
          if(r.status!==200){ box.innerHTML='<div class="note">'+
            esc(r.body.error||'unavailable')+'</div>'; return; }
          trackData=r.body; trackDim='';
          paintMap();
        }
        function paintMap(){
          const box=$('a-map'); if(!box || !trackData) return;
          const pts=trackData.points||[], acts=trackData.actions||[];
          if(!pts.length && !acts.length){
            box.innerHTML='<div class="note">Nothing recorded for '+esc(trackData.player)+' yet.</div>';
            $('a-dims').textContent=''; return;
          }
          // One dimension at a time: overworld and nether coordinates share
          // numbers but not places, and drawing them together is a lie.
          const dims=[...new Set(pts.concat(acts).map(p=>p.dim).filter(Boolean))];
          if(!trackDim || !dims.includes(trackDim)) trackDim=dims[0]||'';
          const P=pts.filter(p=>p.dim===trackDim), A=acts.filter(p=>p.dim===trackDim);

          const dimPick=dims.length>1
            ? dims.map(d=>'<button class="btn'+(d===trackDim?' on':'')+'" '+
                'data-dim="'+esc(d)+'" style="padding:3px 9px;font-size:12px;margin-right:6px">'+
                esc(d)+'</button>').join('')
            : '';
          $('a-dims').innerHTML = dimPick || esc(trackDim);

          // Top-down: x across, z down, which is how Minecraft's own maps read.
          const xs=P.concat(A).map(p=>p.x), zs=P.concat(A).map(p=>p.z);
          let minX=Math.min(...xs), maxX=Math.max(...xs);
          let minZ=Math.min(...zs), maxZ=Math.max(...zs);
          const padBlocks=Math.max(8,(Math.max(maxX-minX,maxZ-minZ))*0.06);
          minX-=padBlocks; maxX+=padBlocks; minZ-=padBlocks; maxZ+=padBlocks;
          // Square the aspect so a corridor doesn't come out as a smear.
          const spanX=maxX-minX, spanZ=maxZ-minZ, span=Math.max(spanX,spanZ,16);
          const cx=(minX+maxX)/2, cz=(minZ+maxZ)/2;
          const W=1000, H=Math.round(W*0.62);
          const sx=v=>((v-cx)/span)*W*0.92+W/2;
          const sz=v=>((v-cz)/span)*H*0.92*(W/H)/(W/H)+H/2;

          const path=P.map((p,i)=>(i?'L':'M')+sx(p.x).toFixed(1)+' '+sz(p.z).toFixed(1)).join(' ');
          const grid=[];
          for(let g=0;g<=4;g++){
            const gx=(W/4)*g, gy=(H/4)*g;
            grid.push('<line x1="'+gx+'" y1="0" x2="'+gx+'" y2="'+H+'" stroke="#1b1f27"/>');
            grid.push('<line x1="0" y1="'+gy+'" x2="'+W+'" y2="'+gy+'" stroke="#1b1f27"/>');
          }
          const dots=A.map((a,i)=>
            '<circle class="mk" data-i="'+i+'" cx="'+sx(a.x).toFixed(1)+'" cy="'+sz(a.z).toFixed(1)+
            '" r="5" fill="'+(ACTION_COLOR[a.action]||'#9aa3ae')+'" fill-opacity=".85" '+
            'stroke="#0b0d11" stroke-width="1.5"/>').join('');
          const start=P.length?'<circle cx="'+sx(P[0].x).toFixed(1)+'" cy="'+sz(P[0].z).toFixed(1)+
            '" r="4" fill="none" stroke="#57c957" stroke-width="2"/>':'';
          const end=P.length?'<circle cx="'+sx(P[P.length-1].x).toFixed(1)+'" cy="'+
            sz(P[P.length-1].z).toFixed(1)+'" r="4" fill="#57c957"/>':'';

          box.innerHTML='<div class="mapwrap">'+
            '<svg viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="xMidYMid meet" role="img" '+
            'aria-label="Movement map for '+esc(trackData.player)+'">'+
            grid.join('')+
            '<path d="'+path+'" fill="none" stroke="#3d6fb5" stroke-width="2.5" '+
            'stroke-linejoin="round" stroke-linecap="round"/>'+
            start+end+dots+'</svg>'+
            '<div class="maptip" id="a-tip"></div>'+
            '<div class="legend" id="a-legend"></div></div>';

          const used=[...new Set(A.map(a=>a.action))];
          $('a-legend').innerHTML=
            '<span><i style="background:#3d6fb5"></i>path</span>'+
            '<span><i style="background:#57c957"></i>latest position</span>'+
            used.map(u=>'<span><i style="background:'+(ACTION_COLOR[u]||'#9aa3ae')+'"></i>'+
              esc(u)+'</span>').join('')+
            '<span class="muted">'+P.length+' points · '+A.length+' actions · '+
            Math.round(span)+' blocks across</span>';

          const svg=box.querySelector('svg'), tip=$('a-tip');
          box.querySelectorAll('.mk').forEach(el=>{
            el.addEventListener('mouseenter',e=>{
              const a=A[+el.getAttribute('data-i')];
              tip.textContent=a.action+(a.count>1?' ×'+a.count:'')+
                (a.detail?' · '+a.detail:'')+' · '+a.x+','+a.y+','+a.z+' · '+fmtAgo(a.at);
              const r=svg.getBoundingClientRect(), b=box.getBoundingClientRect();
              tip.style.left=(r.left-b.left+(+el.getAttribute('cx'))/W*r.width)+'px';
              tip.style.top=(r.top-b.top+(+el.getAttribute('cy'))/H*r.height-26)+'px';
              tip.style.opacity='1';
            });
            el.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          });
          const dimButtons=$('a-dims').querySelectorAll('[data-dim]');
          dimButtons.forEach(b=>b.onclick=()=>{ trackDim=b.getAttribute('data-dim'); paintMap(); });
        }
        let activityRows=[], activityMeta=null;
        async function loadActivity(){
          if(!$('a-rows')) return;
          const r=await jget('/api/activity');
          if(r.status!==200){ $('a-rows').innerHTML='<div class="note">'+
            esc(r.body.error||'unavailable')+'</div>'; return; }
          activityRows=r.body.rows||[]; activityMeta=r.body;
          showAdmins(r.body.admins);
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
            d.appendChild(avatar(e.player,e.uuid,'sm'));
            d.insertAdjacentHTML('beforeend','<span class="ago">'+esc(fmtAgo(e.at).replace(' ago',''))+'</span>'+
              '<span class="who">'+esc(e.player)+
                (e.mask?' <span class="muted" style="font-weight:400">as '+
                  esc(e.mask)+'</span>':'')+'</span>'+
              '<span class="what" style="color:'+col+'">'+esc(e.action)+
                (e.count>1?' &times;'+e.count:'')+'</span>'+
              '<span class="det" title="'+esc(e.detail)+'">'+esc(e.detail)+'</span>'+
              '<span class="at">'+esc(e.where)+'</span>');
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
          trackData=null;
          loadActivity(); loadTrackList();
          const box=$('a-map'); if(box) box.innerHTML='';
        }

        """;

    /**
     * The third chunk: settings, mods and the polling loop.
     *
     * <p>Split for the same reason as the others — see {@link #HTML}.
     */
    private static final String PART3 = """
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
            '<section><h2>Restarting</h2>'+
            '<p class="muted">Restart and Start run this, from this machine. Almin reads it off '+
            'the running server, so it matches however this server was actually launched — '+
            'set <code>web-start-command</code> only if you want something else.</p>'+
            '<div id="s-relaunch" class="note">…</div></section>'+
            '<section><h2>Settings</h2>'+
            '<p class="muted">Written to <code>config/almin/config.json</code> as you change them, '+
            'and live immediately.</p>'+
            '<div id="s-keys"><div class="note">loading…</div></div>'+
            '<button class="btn" id="s-reload" style="margin-top:12px">Reload from disk</button>'+
            '<div class="msg" id="s-msg"></div></section>';
          setTimeout(()=>{
            loadConfig(); loadUpdate(); showRelaunch();
            $('s-pwgo').onclick=setPassword;
            $('s-pw').onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); setPassword(); } };
            $('s-check').onclick=()=>loadUpdate(true);
            $('s-apply').onclick=updateDialog;
            $('s-clearlog').onclick=clearLog;
            $('s-reload').onclick=reloadConfig;
          },0);
          return wrap;
        }
        // What will actually happen when someone presses Restart. A restart
        // that quietly cannot restart is the failure worth naming here.
        function showRelaunch(){
          const box=$('s-relaunch'); if(!box) return;
          if(!canStart){
            box.innerHTML='<span class="state crit">Unavailable</span> '+
              esc(startProblem||'Almin cannot work out how to start this server.');
            return;
          }
          box.innerHTML='<code>'+esc(startCommand)+'</code>'+
            (relaunchError?'<br><span class="state crit">Last attempt failed</span> '+
              esc(relaunchError):'');
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
        let updateInfo=null;
        async function loadUpdate(force){
          const box=$('s-update'), apply=$('s-apply'); if(!box) return;
          box.textContent='checking…'; if(apply) apply.disabled=true;
          // Without force the server answers from a five-minute cache, so
          // opening this tab doesn't call GitHub every time.
          const r=await jget('/api/update'+(force?'?force=1':''));
          if(r.status!==200){ box.textContent='unavailable'; return; }
          const b=r.body;
          updateInfo=b;
          const head='Running <b>v'+esc(b.current)+'</b> · <span class="muted">'+esc(b.repo||'')+'</span>';
          if(b.status==='current'){ box.innerHTML=head+' — up to date.'; }
          else if(b.status==='available'){
            box.innerHTML=head+' — <span class="state warn">v'+esc(b.latest)+' available</span>'+
              (b.hasJar?'':' <span class="muted">(no jar attached to that release)</span>');
            if(apply) apply.disabled=!b.hasJar;
          } else { box.innerHTML=head+' — check failed: '+esc(b.reason||'unknown'); }
        }
        /**
         * The update dialog: what is about to happen, then a countdown.
         *
         * <p>Installing takes the server away and brings it back, and the
         * panel goes with it because the panel is part of what was updated.
         * The old flow said so in a browser confirm() and then left the page
         * looking idle for however long a world takes to boot. This says the
         * same thing, then counts.
         */
        function updateDialog(){
          const to = updateInfo && updateInfo.latest ? 'v'+updateInfo.latest : 'the new version';
          modal('Install '+to,(body,close)=>{
            body.innerHTML=
              '<p>Download <b>'+esc(to)+'</b>, install it, and restart the server.</p>'+
              '<p class="muted">Players are disconnected. This panel is part of what gets '+
              'updated, so the page reloads itself onto the new one — you do not have to '+
              'reload it yourself.</p>'+
              '<div class="row2"><button class="btn go" id="up-go">Download &amp; install</button>'+
              '<button class="btn" id="up-no">Cancel</button></div>'+
              '<div class="msg" id="up-msg"></div>';
            $('up-no').onclick=close;
            $('up-go').onclick=applyUpdate;
          });
        }

        // How long the page waits before reloading itself. Long enough for a
        // small world to be back, short enough to feel like a wait and not an
        // abandonment. It reloads sooner if the server answers sooner.
        const RELOAD_AFTER=20;
        // countGen retires a run: closing the dialog or starting a fresh
        // countdown bumps it, and anything still in flight against the old
        // number stops rather than reloading the page under someone.
        let reloadTimer=null, reloadLeft=0, countGen=0;

        async function applyUpdate(){
          const msg=$('up-msg'), go=$('up-go'), no=$('up-no');
          if(go) go.disabled=true;
          if(msg){ msg.className='msg'; msg.textContent='Downloading…'; }
          const apply=$('s-apply'); if(apply) apply.disabled=true;
          const r=await jpost('/api/update',{restart:true});
          const out=$('s-upmsg');
          if(out){ out.className='msg '+(r.body.ok?'ok':'err');
            out.textContent=r.body.message||r.body.error||'failed'; }
          if(!r.body.restarting){
            // Nothing is going away, so there is nothing to count down to.
            if(msg){ msg.className='msg '+(r.body.ok?'ok':'err');
              msg.textContent=r.body.message||r.body.error||'failed'; }
            if(go) go.disabled=false;
            if(no) no.textContent='Close';
            loadUpdate(true);
            return;
          }
          if(!r.body.relaunch){
            // Stopping, with nothing here to bring it back. Saying "reloading
            // in 20 seconds" would be a promise this cannot keep.
            if(msg){ msg.className='msg';
              msg.textContent=(r.body.message||'Installed.')+
                ' The server is stopping; whatever starts it will bring the panel back.'; }
            if(no) no.textContent='Close';
            return;
          }
          awaitingReturn=true; waitingSince=Date.now();
          showWaiting();
          countdown();
        }

        /** Swaps the dialog into a countdown and starts it. */
        function countdown(){
          const body=$('modal-body');
          if(!body){ startCountdown(); return; }
          const title=$('modal-title');
          if(title) title.textContent='Restarting';
          body.innerHTML=
            '<p class="muted">Installed. The server is starting again.</p>'+
            '<div class="countdown"><div class="cdnum num" id="cd-num">'+RELOAD_AFTER+'</div>'+
            '<div class="cdbar"><i id="cd-bar" style="width:100%"></i></div></div>'+
            '<p class="muted" id="cd-note">This page reloads itself when the count runs out, '+
            'or as soon as the server answers again — whichever comes first.</p>'+
            '<div class="row2"><button class="btn" id="cd-now">Reload now</button></div>';
          $('cd-now').onclick=()=>location.reload();
          // Closing the dialog is a way out, not a trap: it stops the timer.
          // The page still comes back on its own once the server answers, the
          // way it did before any of this existed.
          onScrimClose=stopCountdown;
          startCountdown();
        }

        function startCountdown(){
          stopCountdown();
          const gen=countGen;
          reloadLeft=RELOAD_AFTER;
          paintCountdown();
          reloadTimer=setInterval(()=>{
            reloadLeft--;
            paintCountdown();
            if(reloadLeft>0) return;
            // Not stopCountdown(): that retires this run, and this run is the
            // one about to finish.
            clearInterval(reloadTimer); reloadTimer=null;
            finishCountdown(gen);
          },1000);
        }
        function stopCountdown(){
          countGen++;
          if(reloadTimer){ clearInterval(reloadTimer); reloadTimer=null; }
        }
        function paintCountdown(){
          const n=$('cd-num'), bar=$('cd-bar');
          if(n) n.textContent=Math.max(0,reloadLeft);
          if(bar) bar.style.width=Math.max(0,(reloadLeft/RELOAD_AFTER)*100)+'%';
        }

        /**
         * The count has run out. Reload — but only onto something that is
         * there: reloading at zero onto a server still booting would replace a
         * page explaining itself with the browser's connection error.
         */
        async function finishCountdown(gen){
          if(gen!==countGen) return;             // retired: closed, or superseded
          const note=$('cd-note');
          if(!note) return;                      // dialog gone; poll still has it
          try {
            const r=await fetch('/api/session',
              {credentials:'same-origin',cache:'no-store'});
            if(r.ok){ location.reload(); return; }
          } catch(e){ /* still down */ }
          const n=$('cd-num'); if(n) n.textContent='…';
          const bar=$('cd-bar'); if(bar) bar.style.width='100%';
          note.textContent='Still starting — a big world takes a while. '+
            'This page reloads the moment the server answers.';
          setTimeout(()=>finishCountdown(gen),3000);
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
          if(!confirm('Restart the Minecraft server?\\n\\nPlayers will be disconnected. '+
                      'This page goes quiet for a minute and comes back on its own.')) return;
          $('srvrestart').disabled=true;
          const r=await jpost('/api/server',{action:'restart'});
          if(r.status!==200){ alert(r.body.error||'Restart failed'); $('srvrestart').disabled=false; }
          else if(r.body.relaunch){ awaitingReturn=true; waitingSince=Date.now();
                 $('age').textContent=r.body.message||'restarting…'; showWaiting(); }
          // Nothing here is going to start it again — a wrapper might, or
          // nothing will. Do not sit on a screen promising it comes back.
          else $('age').textContent=r.body.message||'stopping…';
        };
        $('srvstart').onclick=async()=>{
          if(!canStart) return;
          if(!confirm('Start the Minecraft server?\\n\\nThe panel restarts with it and may be '+
                      'briefly unreachable.')) return;
          $('srvstart').disabled=true;
          const r=await jpost('/api/server',{action:'start'});
          if(r.status!==200){ alert(r.body.error||'Start failed'); $('srvstart').disabled=false; }
          else { awaitingReturn=true; waitingSince=Date.now();
                 $('age').textContent='starting server…'; showWaiting(); }
        };

        // ---- advertised mods ----
        // ---- mods ----
        let modsData=null, modSettingsOpen=false;

        function modsPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML=
            '<p class="muted">Mods this server suggests when someone joins. '+
            'Nothing is installed without the player agreeing.</p>'+
            '<div class="bartitle">'+
              '<h2 id="m-count">Advertised mods</h2>'+
              '<span class="spacer"></span>'+
              '<button class="btn go" id="m-add">'+ICON.plus+' Add mod</button>'+
              '<button class="btn cog" id="m-cog" title="Settings for offering mods">'+
                ICON.cog+'</button>'+
            '</div>'+
            '<div id="m-settings"></div>'+
            '<div class="browser" id="modlist"></div>'+
            '<div id="m-unused" style="margin-top:14px"></div>'+
            '<div class="msg" id="m-msg"></div>';
          setTimeout(()=>{
            $('m-add').onclick=()=>menuUnder($('m-add'),addModMenu());
            $('m-cog').onclick=()=>{ modSettingsOpen=!modSettingsOpen; renderModSettings(); };
            $('modlist').oncontextmenu=e=>{
              if(e.target.closest('.modrow')) return;
              menuAt(e,addModMenu());
            };
            loadMods();
          },0);
          return wrap;
        }

        /** The three ways of adding a mod, all landing on the one list. */
        function addModMenu(){
          return [
            {header:'Add a mod'},
            {label:'Search Modrinth…',icon:ICON.globe,hint:'easiest',run:modrinthDialog},
            {label:'Upload a jar…',icon:ICON.box,hint:'hosted here',run:uploadModDialog},
            {label:'Advertise a link…',icon:ICON.edit,hint:'by hand',
             run:()=>editModDialog(null)}
          ];
        }

        /** The settings that govern offering mods, behind the cog. */
        function renderModSettings(){
          const box=$('m-settings'); if(!box) return;
          box.innerHTML='';
          if(!modSettingsOpen || !modsData) return;
          const sec=document.createElement('section');
          sec.style.margin='0 0 13px';
          sec.innerHTML='<h2>Settings</h2>';
          sec.append(
            cfgToggle('mods-advertise','Advertise on join',modsData.advertise,loadMods),
            cfgToggle('mods-deny-kicks','Declining disconnects',modsData.denyKicks,loadMods),
            cfgToggle('require-client-mod','Almin required to play',
                      modsData.requireClientMod,loadMods));
          box.appendChild(sec);
        }

        function modIcon(m){
          const letter=((m.name||m.id||'?').trim().charAt(0)||'?').toUpperCase();
          const stand=()=>{ const s=document.createElement('span'); s.className='modicon';
            s.textContent=letter; return s; };
          if(!m.icon) return stand();
          const img=document.createElement('img'); img.className='modicon';
          img.alt=''; img.loading='lazy';
          img.src='/api/mods/icon?id='+encodeURIComponent(m.id);
          img.onerror=()=>{ if(img.parentNode) img.parentNode.replaceChild(stand(),img); };
          return img;
        }

        function modRow(m){
          const row=document.createElement('div'); row.className='modrow';
          row.appendChild(modIcon(m));
          const body=document.createElement('div'); body.className='body';
          const jar=m.kind==='jar';
          const chips='<span class="chip '+(jar?'jar':'link')+'">'+(jar?'Jar':'Link')+'</span>'+
            (m.source==='modrinth'?' <span class="chip">Modrinth</span>':'')+
            (m.required?' <span class="chip req">Required</span>':'')+
            (m.sha256?' <span class="chip">Pinned</span>':'');
          const where = jar
            ? 'served by this server — '+esc(m.file)
            : esc(m.url);
          body.innerHTML='<div class="ttl">'+esc(m.name||m.id)+
            (m.version?' <span class="muted" style="font-weight:400">'+esc(m.version)+
              '</span>':'')+' '+chips+'</div>'+
            '<div class="sub" title="'+esc(m.url||m.file||'')+'">'+
              '<code>'+esc(m.id)+'</code> · '+where+'</div>';
          const acts=document.createElement('div'); acts.className='acts';
          const edit=document.createElement('button'); edit.className='btn';
          edit.textContent='Edit'; edit.onclick=()=>editModDialog(m);
          const more=document.createElement('button'); more.className='btn cog';
          more.innerHTML='&#8943;'; more.title='More';
          more.onclick=()=>menuUnder(more,modMenu(m));
          acts.append(edit,more);
          row.append(body,acts);
          row.oncontextmenu=ev=>menuAt(ev,modMenu(m));
          return row;
        }

        function modMenu(m){
          const items=[{header:m.name||m.id},
            {label:'Edit…',icon:ICON.edit,run:()=>editModDialog(m)},
            {label:m.required?'Make optional':'Make required',
             run:()=>setModRequired(m,!m.required)}];
          if(m.page) items.push({label:'Open on Modrinth',icon:ICON.globe,
            run:()=>window.open(m.page,'_blank','noopener')});
          items.push('sep');
          items.push({label:'Stop advertising…',icon:ICON.trash,danger:true,
            run:()=>removeModDialog(m)});
          return items;
        }

        async function setModRequired(m,required){
          const r=await jpost('/api/mods/save',{
            id:m.id,name:m.name,version:m.version,url:m.url,file:m.file,
            sha256:m.sha256,required:required,page:m.page||'',source:m.source||''});
          const msg=$('m-msg');
          if(msg){ msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent=r.status===200
              ? (m.name||m.id)+' is now '+(required?'required':'optional')+'.'
              : (r.body.error||'update failed'); }
          loadMods();
        }

        function removeModDialog(m){
          modal('Stop advertising',(body,close)=>{
            body.innerHTML='<p>Stop offering <b>'+esc(m.name||m.id)+'</b> to joining players?</p>'+
              '<p class="muted">'+(m.kind==='jar'
                ? 'The jar stays in <code>config/almin/modfiles/</code> — delete it separately '+
                  'if you want it gone.'
                : 'Nothing is deleted; the link is simply no longer offered.')+'</p>'+
              '<div class="row2"><button class="btn danger" id="rm-go">Stop advertising</button>'+
              '<button class="btn" id="rm-no">Cancel</button></div>'+
              '<div class="msg" id="rm-msg"></div>';
            $('rm-no').onclick=close;
            $('rm-go').onclick=async()=>{
              const r=await jpost('/api/mods/delete',{id:m.id});
              if(r.status===200){ close(); loadMods(); }
              else { const x=$('rm-msg'); x.className='msg err';
                x.textContent=r.body.error||'remove failed'; }
            };
          });
        }

        /**
         * One form for both "edit this mod" and "advertise a link by hand" —
         * they are the same fields, and having two of them was how the panel
         * ended up with a page of stacked forms.
         */
        function editModDialog(m){
          const fresh=!m;
          modal(fresh?'Advertise a mod by hand':'Edit '+(m.name||m.id),(body,close)=>{
            body.innerHTML=
              (fresh?'<p class="muted">Only needed for a mod that is not on Modrinth. The mod id '+
                'must be the one inside <code>fabric.mod.json</code> — not the name on the '+
                'download page — or a client cannot tell it already has the mod. Uploading the '+
                'jar instead lets Almin read it.</p>':'')+
              '<div class="grid2">'+
                '<div><label class="f" id="e-idlabel">Mod id</label>'+
                  '<input id="e-id" placeholder="fabric mod id (e.g. sodium)"></div>'+
                '<div><label class="f">Display name</label><input id="e-name"></div>'+
                '<div><label class="f">Version</label><input id="e-ver"></div>'+
                '<div><label class="f">SHA-256 (optional, recommended)</label>'+
                  '<input id="e-sha"></div>'+
              '</div>'+
              '<label class="f">Where the jar comes from</label>'+
              '<select id="e-src"><option value="">URL (external https link)</option></select>'+
              '<input id="e-url" placeholder="https://… direct link to the .jar" '+
                'style="margin-top:8px">'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:12px">'+
                '<input type="checkbox" id="e-req" style="width:auto"> Required '+
                '(declining can disconnect, if mods-deny-kicks is on)</label>'+
              '<div class="row2"><button class="btn go" id="e-save">Save</button></div>'+
              '<div class="msg" id="e-msg"></div>';
            if(m){
              $('e-id').value=m.id||''; $('e-name').value=m.name||'';
              $('e-ver').value=m.version||''; $('e-sha').value=m.sha256||'';
              $('e-url').value=m.url||''; $('e-req').checked=!!m.required;
              // The jar decides its own id, so editing it here would only
              // produce a second entry the client could never match.
              if(m.kind==='jar'){ $('e-id').disabled=true;
                $('e-idlabel').textContent='Mod id — read from the jar'; }
            }
            const src=$('e-src');
            const showUrl=()=>{ $('e-url').style.display=src.value?'none':''; };
            src.onchange=showUrl;
            jget('/api/mods/files').then(r=>{
              const files=(r.status===200&&r.body.files)?r.body.files:[];
              src.innerHTML='<option value="">URL (external https link)</option>'+
                files.map(f=>'<option value="'+esc(f)+'">served by this server: '+
                  esc(f)+'</option>').join('');
              if(m&&m.file) src.value=m.file;
              showUrl();
            });
            $('e-save').onclick=async()=>{
              const msg=$('e-msg');
              const id=$('e-id').value.trim();
              const file=src.value;
              if(!id){ msg.className='msg err'; msg.textContent='A mod id is required.'; return; }
              if(!file && !$('e-url').value.trim()){
                msg.className='msg err';
                msg.textContent='Pick a file on this server, or paste an https link.'; return; }
              const r=await jpost('/api/mods/save',{
                id:id, name:$('e-name').value.trim(), version:$('e-ver').value.trim(),
                sha256:$('e-sha').value.trim(), file:file,
                url:file?'':$('e-url').value.trim(), required:$('e-req').checked,
                page:m?(m.page||''):'', source:m?(m.source||''):'link'});
              if(r.status!==200){ msg.className='msg err';
                msg.textContent=r.body.error||'save failed'; return; }
              // Changing the id makes a new entry; the old one would otherwise
              // stay behind, advertising the same mod twice.
              if(m && m.id && m.id.toLowerCase()!==id.toLowerCase()){
                await jpost('/api/mods/delete',{id:m.id});
              }
              close(); loadMods();
            };
          });
        }

        function uploadModDialog(){
          modal('Upload a jar',(body,close)=>{
            body.innerHTML='<p class="muted">Stored in <code>config/almin/modfiles/</code> and '+
              'streamed to players over the game connection they already have — no public link, '+
              'nothing else to host. Almin reads the mod id out of the jar and adds it to the '+
              'list, so there is no second step.</p>'+
              '<input type="file" id="m-file" accept=".jar" multiple>'+
              '<div class="row2"><button class="btn go" id="m-upgo">Upload</button></div>'+
              '<div class="msg" id="m-upmsg"></div>';
            $('m-upgo').onclick=()=>uploadMods(close);
          });
        }
        async function uploadMods(close){
          const inp=$('m-file'), msg=$('m-upmsg'), btn=$('m-upgo');
          if(!inp.files||!inp.files.length){
            msg.className='msg err'; msg.textContent='Choose a .jar first.'; return; }
          btn.disabled=true;
          const added=[]; let failed='';
          for(const f of inp.files){
            msg.className='msg'; msg.textContent='Uploading '+f.name+'…';
            try{
              const r=await fetch('/api/mods/upload?name='+encodeURIComponent(f.name),
                {method:'POST',credentials:'same-origin',
                 headers:{'Content-Type':'application/octet-stream'},body:f});
              const b=await r.json().catch(()=>({}));
              if(r.status!==200){ failed=f.name+': '+(b.error||'upload failed'); break; }
              added.push(b.advertised ? (b.modName||b.modId) : f.name+' (stored, but not listed)');
            }catch(e){ failed=f.name+': upload failed — '+e.message; break; }
          }
          btn.disabled=false;
          loadMods();
          if(failed){ msg.className='msg err'; msg.textContent=failed; return; }
          close();
          const out=$('m-msg');
          if(out){ out.className='msg ok';
            out.textContent='Added '+added.join(', ')+' to the list.'; }
        }

        function modrinthDialog(){
          modal('Add from Modrinth',(body)=>{
            body.innerHTML='<p class="muted">Almin downloads the build that fits the Minecraft '+
              'version this server runs and reads the mod id out of the jar, which is the part '+
              'that is easy to get wrong by hand. Search, or paste a link like '+
              '<code>https://modrinth.com/mod/modmenu</code>.</p>'+
              '<div class="term"><input id="mr-q" '+
                'placeholder="search Modrinth, or paste a project link">'+
              '<button class="btn" id="mr-go">Search</button>'+
              '<button class="btn go" id="mr-add">Add link</button></div>'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:9px">'+
              '<input type="checkbox" id="mr-req" style="width:auto"> '+
              'Mark anything added as required</label>'+
              '<div class="msg" id="mr-msg"></div>'+
              '<div id="mr-hits"></div>';
            $('mr-go').onclick=searchModrinth;
            $('mr-add').onclick=()=>addModrinth($('mr-q').value.trim());
            $('mr-q').onkeydown=e=>{
              if(e.key==='Enter'){ e.preventDefault(); searchModrinth(); } };
            $('mr-q').focus();
          },{wide:true});
        }

        async function searchModrinth(){
          const q=$('mr-q').value.trim(), msg=$('mr-msg'), box=$('mr-hits');
          if(!q){ msg.className='msg err'; msg.textContent='Type something to search for.'; return; }
          // A pasted link is not a search; it is the thing itself.
          if(/modrinth\\.com\\//i.test(q)) return addModrinth(q);
          msg.className='msg'; msg.textContent='Searching…'; box.innerHTML='';
          const r=await jpost('/api/mods/modrinth',{action:'search',query:q});
          if(r.status!==200){ msg.className='msg err';
            msg.textContent=r.body.error||'search failed'; return; }
          const hits=r.body.hits||[];
          msg.className='msg';
          msg.textContent=hits.length
            ? hits.length+' Fabric mod'+(hits.length===1?'':'s')+
              ' for Minecraft '+r.body.gameVersion
            : 'Nothing on Modrinth matches that for Minecraft '+r.body.gameVersion+'.';
          box.innerHTML='';
          if(!hits.length) return;
          const list=document.createElement('div'); list.className='browser';
          list.style.marginTop='11px';
          for(const h of hits){
            const row=document.createElement('div'); row.className='modrow';
            // Search results are the one place a picture is linked rather than
            // cached: nothing has been added yet, so there is nothing to cache
            // it against. Once added, the icon is served from this server.
            const img=document.createElement('img'); img.className='modicon';
            img.alt=''; img.loading='lazy'; img.referrerPolicy='no-referrer';
            img.src=h.icon||'';
            img.onerror=()=>{ const s=document.createElement('span'); s.className='modicon';
              s.textContent=((h.title||'?').charAt(0)||'?').toUpperCase();
              if(img.parentNode) img.parentNode.replaceChild(s,img); };
            row.appendChild(img);
            const body=document.createElement('div'); body.className='body';
            body.innerHTML='<div class="ttl">'+esc(h.title)+
              ' <span class="muted" style="font-weight:400">'+
              h.downloads.toLocaleString()+' downloads</span></div>'+
              '<div class="sub">'+esc(h.description)+'</div>';
            const acts=document.createElement('div'); acts.className='acts';
            const add=document.createElement('button'); add.className='btn go';
            add.textContent='Add'; add.onclick=()=>addModrinth(h.slug);
            acts.appendChild(add);
            row.append(body,acts);
            list.appendChild(row);
          }
          box.appendChild(list);
        }
        async function addModrinth(link){
          const msg=$('mr-msg');
          if(!link){ msg.className='msg err'; msg.textContent='Paste a link or search first.'; return; }
          msg.className='msg'; msg.textContent='Fetching '+link+'…';
          const r=await jpost('/api/mods/modrinth',
            {action:'add',link:link,required:$('mr-req').checked});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.body.message||r.body.error||'failed';
          if(r.status===200){ $('mr-q').value=''; loadMods(); }
        }

        async function loadMods(){
          const r=await jget('/api/mods');
          const box=$('modlist'); if(!box) return;
          if(r.status!==200){
            box.innerHTML='<div class="fempty">'+esc(r.body.error||'unavailable')+'</div>';
            return;
          }
          modsData=r.body;
          const mods=r.body.mods||[];
          const count=$('m-count');
          if(count) count.textContent='Advertised mods'+(mods.length?' ('+mods.length+')':'');
          box.innerHTML='';
          if(!mods.length){
            box.innerHTML='<div class="fempty">Nothing advertised yet — '+
              'use <b>Add mod</b>, or right-click here.</div>';
          } else {
            for(const m of mods) box.appendChild(modRow(m));
          }
          paintUnusedJars(r.body.unusedFiles||[]);
          renderModSettings();
        }

        /**
         * Jars in modfiles/ that nothing advertises. Normally none: an upload
         * now makes its own entry. What shows up here is a leftover from
         * before that, or from an offer someone removed.
         */
        function paintUnusedJars(files){
          const box=$('m-unused'); if(!box) return;
          box.innerHTML='';
          if(!files.length) return;
          const sec=document.createElement('section');
          sec.innerHTML='<h2>Jars on this server that nothing offers ('+files.length+')</h2>'+
            '<p class="muted">In <code>config/almin/modfiles/</code>, taking up space but '+
            'never sent to anyone.</p>';
          for(const f of files){
            const row=document.createElement('div'); row.className='row';
            row.style.alignItems='center'; row.style.gap='8px';
            const left=document.createElement('span'); left.className='k';
            left.textContent=f;
            const add=document.createElement('button'); add.className='btn go';
            add.textContent='Advertise'; add.style.marginLeft='auto';
            add.onclick=async()=>{
              // Any id will do: the server reads the real one out of the jar.
              const r=await jpost('/api/mods/save',
                {id:f.replace(/\\.jar$/i,'').toLowerCase().replace(/[^a-z0-9_-]+/g,'-'),
                 file:f, source:'upload'});
              const msg=$('m-msg');
              if(msg){ msg.className='msg '+(r.status===200?'ok':'err');
                msg.textContent=r.status===200?'Advertising '+f+'.':(r.body.error||'failed'); }
              loadMods();
            };
            const del=document.createElement('button'); del.className='btn danger';
            del.textContent='Delete';
            del.onclick=()=>modal('Delete jar',(b,close)=>{
              b.innerHTML='<p>Delete <code>'+esc(f)+'</code> from the server?</p>'+
                '<div class="row2"><button class="btn danger" id="dj-go">Delete</button>'+
                '<button class="btn" id="dj-no">Cancel</button></div>'+
                '<div class="msg" id="dj-msg"></div>';
              $('dj-no').onclick=close;
              $('dj-go').onclick=async()=>{
                const d=await jpost('/api/mods/files/delete',{name:f});
                if(d.status===200){ close(); loadMods(); }
                else { const x=$('dj-msg'); x.className='msg err';
                  x.textContent=d.body.error||'delete failed'; }
              };
            });
            row.append(left,add,del);
            sec.appendChild(row);
          }
          box.appendChild(sec);
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
          restarting=!!s.body.restarting;
          startCommand=s.body.startCommand||''; startProblem=s.body.startProblem||'';
          relaunchError=s.body.relaunchError||'';
          // Absent for a logged-out session, which never asks for a face anyway.
          if(s.body.heads!=null) headsOn=!!s.body.heads;
          if(restarting && !awaitingReturn){ awaitingReturn=true; waitingSince=Date.now(); }
          // A different version answering on this address means the jar was
          // replaced under us and this page is the old panel. Reload onto the
          // new one rather than talking to it with yesterday's script.
          if(version===null) version=s.body.version||'';
          else if(s.body.version && s.body.version!==version){ location.reload(); }
        }
        async function poll(){
          const wasAuthed=authed, wasRunning=serverRunning;
          try { await refreshOnce(); }
          catch(e){
            // Mid-restart this is expected, not an error: the old process has
            // gone and the new one is still booting. Say which of the two it
            // is, and keep asking either way.
            wasReachable=false;
            $('age').textContent = awaitingReturn
              ? 'restarting — waiting for the server…' : 'panel unreachable — retrying';
            if(awaitingReturn) showWaiting();
            return;
          }
          // It answered again after a restart. The panel behind this address
          // may be a different build now, so start clean rather than guess.
          if(awaitingReturn && !wasReachable){ location.reload(); return; }
          wasReachable=true;
          if(awaitingReturn){
            // Still waiting, but the panel is answering. Three ways out: the
            // restart failed and said so, the server is back, or it has been
            // long enough that sitting on a hopeful screen is a lie.
            if(relaunchError || serverRunning || Date.now()-waitingSince>WAIT_LIMIT){
              awaitingReturn=false; render(); return;
            }
            setChrome(); showWaiting(); return;
          }
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
          else if(tab==='term') loadConsole();
          else if(tab==='players') loadPlayers();
          else if(tab==='activity') loadActivity();   // the map only reloads on demand
        }
        $('logout').onclick=async()=>{ await jpost('/api/logout',{}); authed=false; tab='dash'; last=null; render(); };
        (async()=>{ await refreshOnce(); render(); poll(); setInterval(poll,3000); })();
        </script>
        """;

    /**
     * The page, in four pieces.
     *
     * <p>Not a style choice: a single string constant cannot exceed 64KB in a
     * class file, and this page passed that. Joining at runtime keeps each
     * piece under the constant-pool limit — writing {@code PART1 + PART2}
     * instead would not, since the compiler folds that straight back into one
     * constant. The split points follow the page's own sections so that a
     * piece is a readable unit and not an arbitrary cut.
     */
    static final String HTML = String.join("", PART1, PARTFILES, PART2, PART3);
}
