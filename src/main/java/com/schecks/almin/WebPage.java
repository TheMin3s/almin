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
          .bluemapwrap{padding:0;overflow:hidden;background:#080a0e}
          .bluemapwrap iframe{display:block;width:100%;height:min(66vh,620px);border:0;
                              background:#080a0e}
          .bluemapsetup{min-height:360px;display:grid;place-items:center;padding:34px;
                        text-align:center;background:linear-gradient(145deg,#0b0d11,#111823)}
          .bluemapsetup > div{max-width:560px}.bluemapsetup h3{margin:0 0 8px;color:var(--ink)}
          .mapchoice{display:flex;align-items:center;gap:5px;flex-wrap:wrap}
          .mapchoice .state{white-space:nowrap}.mapchoice .btn{padding:5px 10px;font-size:12px}
          .bluepicked{position:absolute;left:12px;bottom:12px;max-width:min(520px,70%);
                      background:rgba(11,13,17,.9);border:1px solid var(--line);
                      border-radius:8px;padding:6px 10px;color:var(--dim);font-size:12px;
                      pointer-events:none}
          .bluepicked strong{color:var(--ink)}
          .legend svg{width:15px;height:15px;display:inline-block;vertical-align:-3px}
          /* ---- the timeline map ---- */
          .maplayout{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;align-items:start}
          .maplayout.side{grid-template-columns:minmax(0,1fr) 310px}
          @media(max-width:1080px){.maplayout.side{grid-template-columns:minmax(0,1fr)}}
          .mapwrap > svg{cursor:grab;touch-action:none}
          .mapwrap > svg.grabbing{cursor:grabbing}
          .onlinebar{position:absolute;left:12px;top:12px;right:64px;display:flex;gap:6px;
                     flex-wrap:wrap;max-height:74px;overflow:hidden}
          .sceneexpand{position:absolute;left:12px;bottom:12px;background:rgba(11,13,17,.9);
                       border:1px solid var(--brand);color:var(--ink);border-radius:8px;
                       padding:6px 10px;font:600 12px/1.2 inherit;cursor:pointer}
          .sceneexpand:hover{background:var(--card2)}
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
          .dims{display:flex;align-items:center;gap:2px;background:var(--card2);
                border:1px solid var(--line);border-radius:8px;padding:2px 2px 2px 9px}
          .dims .lbl{color:var(--mute);font-size:11px;text-transform:uppercase;
                     letter-spacing:.7px;margin-right:5px}
          .dims button{background:none;border:0;color:var(--dim);padding:3px 9px;
                       border-radius:6px;font:inherit;font-size:12px;cursor:pointer}
          .dims button:hover{color:var(--ink)}
          .dims button.on{background:var(--brand);color:#1a1205;font-weight:700}
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
          .live{display:inline-flex;align-items:center;gap:6px;padding:5px 12px;
                border:1px solid #d7484a;border-radius:8px;color:#ff8f90;
                font-size:12.5px;font-weight:700;letter-spacing:.6px}
          .live i{width:8px;height:8px;border-radius:50%;background:#e5484d;
                  animation:pulse 1.8s ease-in-out infinite}
          @keyframes pulse{0%,100%{opacity:1}50%{opacity:.25}}
          @media(prefers-reduced-motion:reduce){.live i{animation:none}}
          /* The settings that live beside the map rather than in the config tab. */
          .mapopts{position:absolute;right:50px;top:12px;width:236px;z-index:4;
                   background:rgba(11,13,17,.96);border:1px solid var(--line);
                   border-radius:10px;padding:11px 12px;font-size:12.5px;
                   box-shadow:0 12px 32px rgba(0,0,0,.5)}
          .mapopts h4{margin:0 0 9px;font-size:10.5px;text-transform:uppercase;
                      letter-spacing:.9px;color:var(--brand)}
          .mapopts label{display:flex;align-items:center;gap:10px;margin:8px 0;color:var(--dim)}
          .mapopts label span{flex:1;min-width:0;line-height:1.35}
          /* Inputs are full-width everywhere else on the page; here they sit
             beside their label, so each one is told its own size. */
          .mapopts input[type=range]{width:92px;flex:none;padding:0;accent-color:var(--brand)}
          .mapopts input[type=checkbox]{width:15px;height:15px;flex:none;padding:0;
                                        accent-color:var(--brand)}
          .mapopts select{width:104px;flex:none;padding:3px 6px;font-size:12px}
          .mapopts hr{border:0;border-top:1px solid var(--line);margin:10px 0 8px}
          .mapopts .onote{color:var(--mute);font-size:11px;margin:-3px 0 6px;
                          font-variant-numeric:tabular-nums}
          .mapopts .chips{display:flex;flex-wrap:wrap;gap:5px;margin:2px 0 4px}
          .mapopts .chips button{background:var(--card2);border:1px solid var(--line);
                                 color:var(--dim);border-radius:999px;padding:2px 9px;
                                 font:inherit;font-size:11px;cursor:pointer}
          .mapopts .chips button.on{border-color:var(--brand);color:var(--brand)}
          .mapopts .row{display:flex;gap:6px;margin-top:10px}
          .mapopts .row .btn{flex:1;padding:4px 8px;font-size:12px}
          /* What a crowd of marks turns into when the map is zoomed out. */
          .clusterbox{position:absolute;z-index:5;max-width:330px;max-height:280px;
                      overflow:auto;background:rgba(11,13,17,.97);border:1px solid var(--line);
                      border-radius:10px;padding:9px 11px;font-size:12.5px;
                      box-shadow:0 12px 32px rgba(0,0,0,.5)}
          .clusterbox h4{margin:0 0 7px;font-size:11px;color:var(--brand);
                         text-transform:uppercase;letter-spacing:.8px;
                         display:flex;align-items:center;gap:8px}
          .clusterbox h4 .shut{margin-left:auto;background:none;border:0;color:var(--dim);
                               font-size:15px;line-height:1;cursor:pointer;padding:0 2px}
          .clusterbox h4 .shut:hover{color:var(--ink)}
          .clusterbox .cl{cursor:pointer}
          .clusterbox .cl .tm{margin-left:auto;color:var(--mute);white-space:nowrap}
          .clusterbox .cl{display:flex;gap:7px;align-items:baseline;padding:3px 0;
                          border-bottom:1px solid rgba(255,255,255,.05)}
          .clusterbox .cl:last-child{border-bottom:0}
          .clusterbox .cl .nm{font-weight:650}
          .clusterbox .cl .xn{color:var(--brand);font-variant-numeric:tabular-nums}
          .clusterbox .cl .dt{color:var(--dim);word-break:break-word}
          /* Episodes: what the rows meant, and what the model made of them. */
          .episode{display:grid;grid-template-columns:19px 1fr auto auto;gap:9px;
                   align-items:baseline;padding:7px 2px;font-size:13px;cursor:pointer;
                   border-bottom:1px solid rgba(255,255,255,.05)}
          .episode:last-child{border-bottom:0}
          .episode:hover{background:var(--card2)}
          .episode .kind{display:inline-block;padding:1px 7px;border-radius:999px;
                         font-size:10.5px;text-transform:uppercase;letter-spacing:.6px;
                         border:1px solid var(--line);color:var(--dim);margin-right:7px}
          .episode .tm{color:var(--mute);font-size:11.5px;white-space:nowrap}
          .summary{background:var(--card2);border:1px solid var(--line);border-radius:10px;
                   padding:12px 14px;line-height:1.55;margin:10px 0}
          .moment{display:flex;gap:9px;align-items:baseline;padding:7px 2px;cursor:pointer;
                  border-bottom:1px solid rgba(255,255,255,.05)}
          .moment:last-child{border-bottom:0}
          .moment:hover{background:var(--card2)}
          .moment .lb{font-weight:650}
          .moment .wy{color:var(--dim)}
          .means{color:var(--brand);font-size:12px;margin-top:2px;opacity:.92}
          .means::before{content:'\u2192 ';opacity:.6}
          /* Fullscreen: the map takes the window and everything else floats
             on top of it, because the point of going fullscreen is the map. */
          .maplayout.fullmap{position:fixed;inset:0;z-index:60;display:block;
                             background:#07090c;padding:0;gap:0}
          .maplayout.fullmap > div{position:static}
          .fullmap #t-map .mapwrap{border:0;border-radius:0;padding:0;height:100vh}
          .fullmap #t-map .mapwrap > svg{height:100vh;max-height:100vh}
          .fullmap #t-map .bluemapwrap iframe{height:100vh;max-height:100vh}
          .fullmap .bluepicked{bottom:150px}
          .fullmap .timeline{position:absolute;left:14px;right:14px;bottom:60px;margin:0}
          .fullmap .timeline svg{height:70px;opacity:.94;
                                 box-shadow:0 8px 26px rgba(0,0,0,.55)}
          .fullmap .tlbar{position:absolute;left:14px;right:14px;bottom:12px;margin:0;
                          background:rgba(11,13,17,.76);border:1px solid var(--line);
                          border-radius:10px;padding:7px 11px;
                          backdrop-filter:blur(7px);-webkit-backdrop-filter:blur(7px)}
          .fullmap .mapside{position:absolute;right:14px;top:14px;width:330px;
                            max-height:calc(100vh - 160px);
                            background:rgba(11,13,17,.76);
                            backdrop-filter:blur(7px);-webkit-backdrop-filter:blur(7px)}
          .fullmap .legend{position:absolute;left:14px;bottom:150px;right:360px;margin:0;
                           background:rgba(11,13,17,.72);border:1px solid var(--line);
                           border-radius:9px;padding:7px 10px;
                           backdrop-filter:blur(7px);-webkit-backdrop-filter:blur(7px)}
          /* The floating side list would otherwise sit on top of the map's own
             buttons, which are the way back out of fullscreen. */
          .fullmap.side .mapbtns{right:358px}
          .fullmap.side .onlinebar{right:410px}
          .fullmap .onlinebar{top:14px;left:14px}
          @media(max-width:900px){.fullmap .mapside{display:none}
                                  .fullmap .legend{right:14px}
                                  .fullmap.side .mapbtns{right:12px}
                                  .fullmap.side .onlinebar{right:64px}}
          /* One player in the players list: the row, who they look like, and
             the two little pictures of what they have been up to. */
          .pcard{border-bottom:1px solid rgba(255,255,255,.06);padding:4px 0 12px}
          .pcard:last-child{border-bottom:0}
          .wearing{display:flex;align-items:center;gap:8px;margin:2px 0 0 46px;
                   font-size:12.5px;padding:5px 9px;border:1px solid var(--line);
                   border-radius:8px;background:var(--card2);width:max-content;max-width:100%}
          .wearing .face{width:20px;height:20px;border-radius:4px}
          .pstrips{display:flex;gap:12px;flex-wrap:wrap;margin:9px 0 0 46px;align-items:flex-start}
          .pstrip{background:var(--card2);border:1px solid var(--line);border-radius:9px;
                  padding:7px 9px;position:relative}
          .pstrip .none{color:var(--mute);font-size:12px}
          .acts{display:flex;gap:15px 16px;flex-wrap:wrap;align-items:center;
                max-width:min(520px,100%);padding:9px 11px 11px}
          .acell{position:relative;display:inline-flex;line-height:0}
          .acell i{position:absolute;right:-8px;bottom:-7px;font-style:normal;
                   font-size:9px;font-weight:700;background:#0b0d11;color:var(--brand);
                   border:1px solid var(--line);border-radius:999px;padding:1px 4px;
                   line-height:1.15;font-variant-numeric:tabular-nums}
          .mini{padding:5px;width:162px}
          .mini svg{display:block;border-radius:6px}
          .mini button.go{position:absolute;right:8px;top:8px;width:22px;height:22px;
                          padding:0;display:flex;align-items:center;justify-content:center;
                          background:rgba(11,13,17,.86);border:1px solid var(--line);
                          border-radius:6px;color:var(--ink);cursor:pointer}
          .mini button.go:hover{border-color:var(--brand);color:var(--brand)}
          .mini button.go svg{width:13px;height:13px}
          /* Which kinds of thing the map is showing, when it is not all of them. */
          .filterbox{margin-top:10px;background:var(--card2);border:1px solid var(--line);
                     border-radius:11px;padding:11px 13px}
          .fhead{display:flex;align-items:center;gap:10px;margin-bottom:9px;font-size:13px}
          .fhead .btn{padding:4px 10px;font-size:12px}
          .fhead .btn:last-child{margin-left:0}
          .fhead .muted{margin-right:auto}
          .fgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}
          .fcat h5{margin:0 0 5px;font-size:10.5px;text-transform:uppercase;
                   letter-spacing:.9px;color:var(--brand)}
          .fline{display:flex;align-items:center;gap:7px;font-size:12.5px;padding:2px 0;
                 color:var(--dim);cursor:pointer}
          .fline:hover{color:var(--ink)}
          .fline input{width:14px;height:14px;flex:none;padding:0;accent-color:var(--brand)}
          .fline span{flex:1;min-width:0;display:flex;align-items:center;gap:5px;
                      overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
          .fline i{font-style:normal;color:var(--mute);font-size:11px;
                   font-variant-numeric:tabular-nums}
          .fmore{background:none;border:0;color:var(--mute);font:inherit;font-size:11.5px;
                 cursor:pointer;padding:1px 0 3px 21px}
          .fmore:hover{color:var(--brand)}
          .fsub{margin:0 0 6px 21px;padding-left:8px;border-left:1px solid var(--line);
                max-height:190px;overflow:auto}
          /* What a stretch of work built, in isometric. */
          .scene{background:#0b0d11;border:1px solid var(--line);border-radius:10px;
                 overflow:hidden}
          .scene svg{display:block;width:100%;height:min(420px,52dvh)}
          .scenebar{display:flex;gap:9px;align-items:center;margin-top:10px;flex-wrap:wrap}
          .scenebar .btn{padding:5px 11px;font-size:12.5px}
          .scenebar input[type=range]{flex:1;min-width:140px;accent-color:var(--brand);padding:0}
          .scenepick{margin-top:9px;padding:7px 10px;border:1px solid var(--line);
                     border-radius:8px;background:#0b0d11;color:var(--dim);font-size:12px}
          .scenepick strong{color:var(--ink)}
          .scenekey{display:flex;gap:14px;flex-wrap:wrap;margin-top:9px;font-size:12px;
                    color:var(--dim);align-items:center}
          .scenekey i{display:inline-block;width:10px;height:10px;border-radius:2px;
                      margin-right:5px;vertical-align:-1px}
          .subtabs{display:flex;gap:6px;margin-bottom:14px;flex-wrap:wrap}
          .subtabs button{background:var(--card2);border:1px solid var(--line);
                          color:var(--dim);border-radius:9px;padding:6px 14px;
                          font:inherit;font-size:13px;cursor:pointer}
          .subtabs button:hover{color:var(--ink)}
          .subtabs button.on{border-color:var(--brand);color:var(--brand);font-weight:650}
          .sprow{display:grid;grid-template-columns:minmax(170px,270px) minmax(0,1fr) auto;
                 gap:10px;align-items:center;padding:5px 0;
                 border-bottom:1px solid rgba(255,255,255,.05)}
          .sprow:last-child{border-bottom:0}
          .sprow code{font-size:12.5px;color:var(--dim);word-break:break-all}
          .sprow input,.sprow select{padding:6px 9px;font-size:12.5px}
          .sprow .btn{padding:4px 10px;font-size:11.5px;visibility:hidden}
          .sprow.edited code{color:var(--brand)}
          .sprow.edited input,.sprow.edited select{border-color:var(--brand)}
          .sprow.edited .btn{visibility:visible}
          .modbadge{font-size:10px;font-weight:700;letter-spacing:.6px;text-transform:uppercase;
                    border-radius:999px;padding:2px 8px;border:1px solid var(--line);
                    color:var(--mute);white-space:nowrap}
          .modbadge.yes{border-color:#3d7a4a;color:#8ee0a1;background:rgba(61,122,74,.16)}
          .modbadge.no{border-color:var(--line);color:var(--mute)}
          .facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:8px 14px}
          .facts div{display:flex;gap:8px;align-items:baseline;font-size:13px;
                     border-bottom:1px solid rgba(255,255,255,.05);padding:4px 0}
          .facts span{color:var(--dim);min-width:120px}
          .facts b{color:var(--ink);font-weight:600;word-break:break-word}
          .csec{margin:16px 0 6px;font-size:11px;text-transform:uppercase;letter-spacing:.9px;
                color:var(--brand)}
          .aiform{display:grid;gap:9px;margin-top:12px;max-width:620px}
          .aiform label{display:flex;align-items:center;gap:10px}
          .aiform label span{flex:none;width:150px;color:var(--dim);font-size:13px}
          .aiform input,.aiform select{flex:1;min-width:0;padding:7px 10px;font-size:13px}
          .airow{display:flex;gap:8px;flex-wrap:wrap;margin-top:11px}
          @media(max-width:620px){.aiform label{flex-wrap:wrap}
                                  .aiform label span{width:100%}}
          .cmod{display:flex;gap:8px;align-items:baseline;padding:4px 2px;font-size:12.5px;
                border-bottom:1px solid rgba(255,255,255,.05)}
          .cmod:last-child{border-bottom:0}
          .cmod code{color:var(--ink)}
          .cmod .ver{color:var(--mute);font-size:11.5px}
          .cmod .when{margin-left:auto;color:var(--mute);font-size:11px;white-space:nowrap}
          .cmod .plus{font-style:normal;color:#8ee0a1;font-weight:700}
          .cmod .minus{font-style:normal;color:#ff8f90;font-weight:700}
          .cmod.fresh code{color:#8ee0a1}
          .cmod.gone{opacity:.65}
          .cmod.gone code{text-decoration:line-through}
          .cmod .ban{border:1px solid #d7484a;color:#ff8f90;border-radius:999px;
                     padding:0 7px;font-size:10px;text-transform:uppercase;letter-spacing:.5px}
          .maptip{position:absolute;pointer-events:none;background:#0b0d11;
                  border:1px solid var(--line);border-radius:6px;padding:5px 9px;font-size:12px;
                  color:var(--ink);max-width:340px;opacity:0;transition:opacity .1s;z-index:3;
                  line-height:1.4;box-shadow:0 6px 18px rgba(0,0,0,.5)}
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
                 align-items:flex-start;justify-content:center;padding:clamp(8px,4vh,38px) 16px;
                 overflow:hidden}
          .modal{background:var(--card);border:1px solid var(--line);border-radius:14px;
                 width:min(880px,100%);padding:17px 20px 20px;
                 box-shadow:0 24px 60px rgba(0,0,0,.55);max-height:calc(100dvh - 16px);
                 display:flex;flex-direction:column;overflow:hidden}
          .modal.wide{width:min(1100px,100%)}
          .modal #modal-body{min-height:0;overflow:auto;overscroll-behavior:contain}
          .modal h3{margin:0;font-size:15.5px;font-weight:650}
          .mtop{display:flex;align-items:center;gap:10px;margin-bottom:13px}
          .mtop .btn{margin-left:auto}
          .modal .grid2{display:grid;grid-template-columns:1fr 1fr;gap:9px}
          @media(max-width:620px){.modal .grid2{grid-template-columns:1fr}}
          .modal label.f{display:block;font-size:11.5px;text-transform:uppercase;
                         letter-spacing:.8px;color:var(--mute);font-weight:600;margin:11px 0 4px}
          .modal .row2{display:flex;gap:8px;align-items:center;margin-top:13px;flex-wrap:wrap}
          @media(max-height:700px){.scene svg{height:min(330px,45dvh)}}
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
        async function jpost(u,d){
          let r;
          try {
            r=await fetch(u,{method:'POST',credentials:'same-origin',
              headers:{'Content-Type':'application/json'},body:JSON.stringify(d||{})});
          } catch(e){
            // The request never completed. Without this the promise rejects,
            // nothing catches it, and the button sits on whatever it last
            // said \u2014 which reads as "it hung and then failed".
            return {status:0, body:{error:'No answer from the panel itself ('+
              (e&&e.message?e.message:'connection lost')+'). Something between '+
              'your browser and Almin dropped it \u2014 a reverse proxy in front '+
              'of the panel will do this at its own timeout.'}};
          }
          return {status:r.status, body:await r.json().catch(()=>({}))}; }

        /**
         * Why a call failed, in words, when the server did not supply any.
         *
         * <p>A bare "failed" is the least useful thing a panel can say. A
         * status with no message in it did not come from Almin \u2014 Almin
         * always sends one \u2014 so it came from whatever is in front of it.
         */
        function why(r,fallback){
          if(r&&r.body&&r.body.error) return r.body.error;
          if(r&&r.status) return 'The server answered '+r.status+' with no message. '+
            'Almin always sends one, so that came from something in front of it.';
          return fallback||'failed';
        }

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
        /**
         * A player's face, or their initial if there isn't one to be had.
         *
         * @param byName ask by name alone, with no UUID — which is the only
         *               thing a mask gives you, since a mask is a display name
         *               somebody typed and may name any account or none
         */
        function avatar(name,uuid,size,byName){
          const cls='face'+(size?' '+size:'');
          const letter=((name||'?').trim().charAt(0)||'?').toUpperCase();
          const stand=()=>{ const s=document.createElement('span'); s.className=cls;
            s.textContent=letter; s.style.background='hsl('+nameHue(name)+' 45% 62%)';
            s.title=name||''; return s; };
          if(!headsOn || (!uuid && !byName)) return stand();
          const img=document.createElement('img'); img.className=cls;
          img.alt=''; img.loading='lazy'; img.title=name||'';
          img.src=uuid
            ? '/api/head?uuid='+encodeURIComponent(uuid)+'&name='+encodeURIComponent(name||'')
            : '/api/head?name='+encodeURIComponent(name||'');
          img.onerror=()=>{ if(img.parentNode) img.parentNode.replaceChild(stand(),img); };
          return img;
        }

        let openMenu=null, openScrim=null, onScrimClose=null;
        /**
         * The listener that dismisses the open menu, held so it can be taken
         * off again.
         *
         * <p>It used to be added with {@code {once:true}} and never removed.
         * A menu closed any other way — Escape, or clicking one of its own
         * items — left that listener armed, and the very next click anywhere
         * spent it. So the click that opened the *next* menu reached the
         * leftover listener a moment after that menu was built, and closed it
         * on the spot. One menu in two worked, which is exactly what a
         * finicky button feels like.
         */
        let menuDismiss=null;
        function closeMenu(){
          if(menuDismiss){ document.removeEventListener('click',menuDismiss); menuDismiss=null; }
          if(openMenu){ openMenu.remove(); openMenu=null; }
        }
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
          // Tied to this menu: if it has already gone by the time the tick
          // comes round, nothing is armed.
          const dismiss=()=>closeMenu();
          menuDismiss=dismiss;
          setTimeout(()=>{
            if(menuDismiss===dismiss && openMenu===m) document.addEventListener('click',dismiss);
          },0);
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
        /**
         * When this player's last visit began.
         *
         * <p>Read off the path rather than off join and leave rows, which are
         * only in the log if that player was being recorded at the time.
         * Samples stop while somebody is offline, so a gap longer than a
         * session's worth of standing still is where the last one started.
         */
        const SESSION_GAP=20*60*1000;
        function lastSessionFrom(name){
          const pts=((peopleData&&peopleData.tracks)||{})[name]||[];
          if(pts.length<2) return 0;
          let from=pts[pts.length-1].at;
          for(let i=pts.length-1;i>0;i--){
            if(pts[i].at-pts[i-1].at>SESSION_GAP) break;
            from=pts[i-1].at;
          }
          return from;
        }

        function playerRow(p,sub,since,offline){
          const card=document.createElement('div'); card.className='pcard';
          const row=document.createElement('div'); row.className='row';
          row.style.alignItems='center'; row.style.gap='10px';
          row.appendChild(avatar(p.name,p.uuid,'lg'));
          const left=document.createElement('span'); left.className='k'; left.style.whiteSpace='normal';
          // Real name first and always: an admin screen that showed only the
          // mask would be the one place the mask was not supposed to work.
          left.innerHTML='<b style="color:var(--ink)">'+esc(p.name)+'</b>'+
            '<br><span class="muted" style="font-size:12px">'+esc(sub)+'</span>';
          const set=document.createElement('button'); set.className='btn';
          set.textContent=p.mask?'Change mask':'Set mask'; set.style.marginLeft='auto';
          set.onclick=()=>{ const v=prompt('Display name for '+p.name+':', p.mask||'');
            if(v===null) return;
            const t=v.trim();
            sendMask(p.name, t, t===''); };
          // Whether Almin can talk to this client at all. It is the first
          // thing you want to know about a player and the answer to half the
          // questions that follow.
          const badge=document.createElement('span');
          const modded=p.hasMod!==undefined?!!p.hasMod:!!p.reported;
          badge.className='modbadge'+(modded?' yes':' no');
          badge.textContent=modded?'Almin':'vanilla';
          badge.title=modded
            ? 'This client has the Almin mod'
            : (p.hasMod===undefined
                ? 'This client never reported the Almin mod'
                : 'This client does not have the Almin mod');
          const see=document.createElement('button'); see.className='btn';
          see.textContent='Activity';
          see.title='Open this player on the activity map';
          see.onclick=()=>openInActivity(p.name);
          const kit=document.createElement('button'); kit.className='btn';
          kit.textContent='Client';
          kit.disabled=!p.reported;
          kit.title=p.reported
            ? 'What this client is running, and what changed'
            : 'This client has not reported what it is running';
          kit.onclick=()=>showClient(p);
          row.append(badge,left,kit,see,set);
          if(p.mask){ const c=document.createElement('button'); c.className='btn danger'; c.textContent='Clear';
            c.onclick=()=>sendMask(p.name,'',true); row.appendChild(c); }
          card.appendChild(row);

          // The account they are wearing, as its own small row. A mask is
          // another player's name as far as everyone else is concerned, and
          // the useful question — "who does this look like" — is answered by
          // the face rather than by the string.
          if(p.mask){
            const worn=document.createElement('div');
            worn.className='wearing';
            worn.appendChild(avatar(p.mask,'','sm',true));
            const t=document.createElement('span');
            t.innerHTML='<span class="muted">appears to players as</span> '+
              '<b style="color:var(--brand)">'+esc(p.mask)+'</b>';
            worn.appendChild(t);
            card.appendChild(worn);
          }

          const strips=document.createElement('div');
          strips.className='pstrips';
          strips.appendChild(actionStrip(p.name,since,offline));
          strips.appendChild(pathMap(p.name,since,offline));
          card.appendChild(strips);
          return card;
        }

        /**
         * Everything this player did, one icon per kind, with how many.
         *
         * @param since only from this moment on, which for somebody who is
         *              connected means this session — "what have they been
         *              doing" asked about a player who is here is a question
         *              about now, not about last week
         */
        function actionStrip(name,since,offline){
          const box=document.createElement('div');
          box.className='pstrip acts';
          if(since) box.setAttribute('title',offline?'Their last visit only':'This session only');
          const acts=(peopleData&&peopleData.actions||[])
            .filter(a=>a.player===name && (!since || a.at>=since));
          if(!acts.length){
            // Saying which nothing it is: "nothing this session" and "nothing
            // ever" look identical otherwise, and they mean different things.
            box.innerHTML='<span class="none">'+
              (since?(offline?'nothing on their last visit':'nothing this session')
                    :'nothing recorded')+'</span>';
            return box;
          }
          const by=new Map();
          for(const a of acts) by.set(a.action,(by.get(a.action)||0)+Math.max(1,a.count||1));
          const order=[...by.entries()].sort((x,y)=>y[1]-x[1]);
          for(const [action,n] of order){
            const cell=document.createElement('span');
            cell.className='acell';
            cell.title=n+' × '+action;
            cell.innerHTML='<svg viewBox="-11 -11 22 22" width="22" height="22">'+
              marker(action,0,0,ACTION_COLOR[action]||'#9aa3ae',1.15)+'</svg>'+
              '<i>'+(n>999?'999+':n)+'</i>';
            box.appendChild(cell);
          }
          return box;
        }

        /**
         * Where this player went, small.
         *
         * <p>Its own framing rather than the big map's: someone who spent the
         * day in one room and someone who walked to the badlands both get a
         * picture that fills the box, and the bar underneath says which is
         * which. Without the bar the two would look identical, which would be
         * worse than no map at all.
         */
        function pathMap(name,since,offline){
          const box=document.createElement('div');
          box.className='pstrip mini';
          const all=((peopleData&&peopleData.tracks)||{})[name]||[];
          const pts=since?all.filter(q=>q.at>=since):all;
          if(pts.length<2){
            box.innerHTML='<span class="none">'+
              (since?(offline?'did not move last visit':'has not moved this session')
                    :'no path recorded')+'</span>';
            return box;
          }
          // Where they are now, unless they have only just arrived there:
          // one step through a portal is not a path, and answering "no path
          // recorded" for somebody with an afternoon of walking behind them
          // because their last point is in the Nether is the wrong answer.
          const counts={};
          for(const q of pts) counts[q.dim]=(counts[q.dim]||0)+1;
          let dim=pts[pts.length-1].dim;
          if((counts[dim]||0)<2){
            for(const d of Object.keys(counts)) if(counts[d]>(counts[dim]||0)) dim=d;
          }
          const here=pts.filter(q=>q.dim===dim);
          if(here.length<2){
            box.innerHTML='<span class="none">'+
              (since?(offline?'did not move last visit':'has not moved this session')
                    :'no path recorded')+'</span>';
            return box;
          }
          const xs=here.map(q=>q.x), zs=here.map(q=>q.z);
          const minX=Math.min(...xs), maxX=Math.max(...xs);
          const minZ=Math.min(...zs), maxZ=Math.max(...zs);
          const cx=(minX+maxX)/2, cz=(minZ+maxZ)/2;
          const W=150, H=84;
          // Fitted on both axes, not one: scaling everything by the wider of
          // the two put a tall path off the top of a box that is not square.
          const scale=Math.min(W/(Math.max(maxX-minX,16)*1.15),
                               H/(Math.max(maxZ-minZ,16)*1.15));
          const span=W/scale;
          const px=v=>(v-cx)*scale+W/2;
          const pz=v=>(v-cz)*scale+H/2;
          const c=playerColor(name);
          const d=here.map((q,i)=>(i?'L':'M')+px(q.x).toFixed(1)+' '+pz(q.z).toFixed(1)).join(' ');
          const last=here[here.length-1];
          // A round number of blocks, drawn to scale, so "how zoomed out is
          // this" has an answer you can read rather than infer.
          const nice=[8,16,32,64,128,256,512,1024,2048,4096];
          let bar=nice[0];
          for(const v of nice) if(v<=span*0.6) bar=v;
          const barPx=bar*scale;
          // Whatever ground there is under this path, darkened so the line is
          // still the thing you see.
          const id='mini'+Math.random().toString(36).slice(2,8);
          const ground=[];
          for(const p of shotsFor(dim,here[here.length-1].at)){
            const x0=px(p.minX), z0=pz(p.minZ);
            const w=px(p.minX+p.span)-x0, h=pz(p.minZ+p.span)-z0;
            if(x0+w<0 || x0>W || z0+h<0 || z0>H) continue;
            ground.push('<image href="/api/map?at='+p.at+'&dim='+encodeURIComponent(dim)+
              '" x="'+x0.toFixed(1)+'" y="'+z0.toFixed(1)+'" width="'+w.toFixed(1)+
              '" height="'+h.toFixed(1)+'" preserveAspectRatio="none" '+
              'style="image-rendering:pixelated"/>');
          }
          box.innerHTML='<svg viewBox="0 0 '+W+' '+H+'" width="100%" height="'+H+'" '+
            'preserveAspectRatio="xMidYMid meet">'+
            '<defs><clipPath id="'+id+'"><rect x="0" y="0" width="'+W+'" height="'+H+
            '"/></clipPath></defs>'+
            '<rect x="0" y="0" width="'+W+'" height="'+H+'" fill="#0b0d11"/>'+
            (ground.length?'<g clip-path="url(#'+id+')">'+ground.join('')+
              '<rect x="0" y="0" width="'+W+'" height="'+H+
              '" fill="#0a0c10" opacity=".46"/></g>':'')+
            '<path d="'+d+'" fill="none" stroke="'+c+'" stroke-width="1.6" '+
            'stroke-linejoin="round" stroke-linecap="round" stroke-opacity=".9"/>'+
            '<circle cx="'+px(last.x).toFixed(1)+'" cy="'+pz(last.z).toFixed(1)+
            '" r="2.6" fill="'+c+'" stroke="#0a0c10" stroke-width="1"/>'+
            '<line x1="6" y1="'+(H-7)+'" x2="'+(6+barPx).toFixed(1)+'" y2="'+(H-7)+
            '" stroke="#9aa3ae" stroke-width="1.4"/>'+
            '<line x1="6" y1="'+(H-10)+'" x2="6" y2="'+(H-4)+'" stroke="#9aa3ae" stroke-width="1.4"/>'+
            '<line x1="'+(6+barPx).toFixed(1)+'" y1="'+(H-10)+'" x2="'+(6+barPx).toFixed(1)+
            '" y2="'+(H-4)+'" stroke="#9aa3ae" stroke-width="1.4"/>'+
            '<text x="'+(10+barPx).toFixed(1)+'" y="'+(H-4)+'" fill="#9aa3ae" '+
            'font-size="9">'+bar+' blocks</text>'+
            '<text x="6" y="11" fill="#5b6472" font-size="9">'+esc(dim)+'</text>'+
            '</svg><button class="go" title="Open on the activity map">'+ICON.globe+'</button>';
          box.querySelector('button').onclick=()=>openInActivity(name);
          return box;
        }

        /** Takes the activity tab to one player, from anywhere else. */
        function openInActivity(name){
          focusPlayer=name;
          tab='activity';
          render();
        }
        // Paths and actions for the players list, fetched once for the whole
        // page rather than once per row. It is the same payload the map uses.
        let peopleData=null, peopleAt=0;
        async function loadPeople(){
          if(peopleData && Date.now()-peopleAt<15000) return;
          const r=await jget('/api/track?all=1');
          if(r.status===200){ peopleData=r.body; peopleAt=Date.now(); }
          // The little maps want ground under them for the same reason the big
          // one does: a line on black says where somebody walked, and a line
          // over the world says where they went.
          if(!shots.length){
            const m=await jget('/api/map');
            if(m.status===200 && m.body.shots){ shots=m.body.shots; }
          }
          await loadBlockColours();
        }

        // What each block looks like, and whether there are item textures to
        // draw tools with. Asked for once; it cannot change while the server
        // is up.
        let blockColour=null;
        async function loadBlockColours(){
          if(blockColour) return;
          const r=await jget('/api/blocks');
          if(r.status!==200){ blockColour={}; return; }
          blockColour=r.body.blocks||{};
          toolTextures=!!(r.body.textures && r.body.textures!=='none');
        }

        async function loadPlayers(){
          const on=$('p-online'); if(!on) return;
          await loadPeople();
          const r=await jget('/api/players');
          if(r.status!==200){ on.innerHTML='<section><div class="note">'+
            esc(r.body.error||'unavailable')+'</div></section>'; return; }
          const online=r.body.online||[];
          const hist=(r.body.history||[]).slice().sort((a,b)=>b.lastSeen-a.lastSeen);
          on.innerHTML='';
          const s1=document.createElement('section');
          s1.innerHTML='<h2>Online ('+online.length+' / '+(r.body.maxPlayers||0)+')</h2>';
          if(!online.length) s1.insertAdjacentHTML('beforeend','<div class="note">Nobody is connected.</div>');
          const now=Date.now();
          const here=new Set(online.map(p=>p.name));
          for(const p of online){
            // What they have done since they joined, not since the log began.
            const since=p.sessionMillis>0?now-p.sessionMillis:0;
            s1.appendChild(playerRow(p, fmtDur(p.sessionMillis)+' this session', since));
          }
          on.appendChild(s1);
          const hs=$('p-hist'); hs.innerHTML='';
          const s2=document.createElement('section');
          s2.innerHTML='<h2>Seen before ('+hist.length+')</h2>';
          if(!hist.length) s2.insertAdjacentHTML('beforeend','<div class="note">No history recorded yet.</div>');
          for(const p of hist.slice(0,150)){
            // Somebody who is offline is asked about the last time they were
            // on. Their whole path over five days is a scribble; the walk they
            // took before logging off is a thing you can read.
            const since=here.has(p.name)?0:lastSessionFrom(p.name);
            s2.appendChild(playerRow(p, p.joins+' join'+(p.joins===1?'':'s')+' · '+
              fmtDur(p.playtimeMillis)+' played · last seen '+fmtAgo(p.lastSeen), since,
              !here.has(p.name)));
          }
          hs.appendChild(s2);
        }
        /**
         * What one client is running.
         *
         * <p>Present mods in alphabetical order, ones that arrived since the
         * last join marked and green, and ones that have gone at the bottom
         * for as long as the history window keeps them. That ordering is the
         * point: the answer to "what changed" should not need reading.
         */
        async function showClient(p){
          modal('What '+p.name+' is running', body=>{
            body.innerHTML='<div class="note">loading…</div>';
            jget('/api/client?uuid='+encodeURIComponent(p.uuid)).then(r=>{
              if(r.status!==200 || !r.body){ body.innerHTML='<div class="note">unavailable</div>';
                return; }
              const c=r.body;
              if(!c.enabled){
                body.innerHTML='<div class="note"><b>Client reporting is off.</b> '+
                  'Turn on <code>client-report</code> to let the Almin client mod say '+
                  'what it is running.</div>';
                return;
              }
              if(!c.known){
                body.innerHTML='<div class="note">This client has not reported anything. '+
                  'It happens when they have not joined since reporting was turned on, '+
                  'or they are not running the Almin mod.</div>';
                return;
              }
              const facts=[
                ['Minecraft',c.minecraft],['Loader',c.loader],['Launcher',c.launcher],
                ['System',[c.os,c.osVersion].filter(Boolean).join(' ')],
                ['Architecture',c.arch],['Java',c.java],
                ['Processors',c.cores?String(c.cores):''],
                ['Memory given to Java',c.memoryMb?c.memoryMb+' MB':'']];
              let html='<div class="facts">';
              for(const [k,v] of facts){
                if(!v) continue;
                html+='<div><span>'+esc(k)+'</span><b>'+esc(v)+'</b></div>';
              }
              html+='</div>'+
                '<p class="muted" style="margin:10px 0 4px">Reported by the client itself '+
                esc(fmtAgo(c.at))+'. A modified client can say anything, so this is a '+
                'support tool rather than proof of anything.</p>';
              const mods=c.mods||[], gone=c.removed||[];
              const own=mods.filter(m=>!groupOf(m)), bundles=bundlesOf(mods);
              html+='<div class="bartitle" style="margin:16px 0 2px">'+
                '<h3 class="csec" style="margin:0">Installed ('+own.length+')</h3>'+
                '<span class="spacer"></span>'+
                '<button class="btn" id="cl-ask" title="Ask the model what these mods do">'+
                'Ask about these</button></div>'+
                '<div id="cl-review"></div>'+
                '<div id="cl-mods"></div><div id="cl-bundled"></div>';
              if(gone.length){
                html+='<h3 class="csec">Removed in the last '+(c.historyDays||7)+
                  ' days ('+gone.length+')</h3><div id="cl-gone"></div>';
              }
              body.innerHTML=html;
              clientReview=null;
              paintMods($('cl-mods'),own,c.at,false);
              paintBundles($('cl-bundled'),bundles,c.at);
              if(gone.length) paintMods($('cl-gone'),gone,c.at,true);
              const ask=$('cl-ask');
              if(ask) ask.onclick=()=>reviewMods(p,c);
            });
          });
        }

        /**
         * Which mod ships this one, if it is not one somebody installed.
         *
         * <p>The client says so where it can. Where it cannot — an older
         * client mod that predates the field — every {@code fabric-*} module
         * is Fabric API, which is by far the largest case and the one the list
         * was drowning in.
         */
        function groupOf(m){
          const id=m.id||'';
          // Fabric API is itself called fabric-api, so the fallback below
          // would fold the parent into its own children and leave the list
          // one short with nothing to say why.
          const g = m.parent ? m.parent : (/^fabric-/.test(id) ? 'fabric-api' : '');
          return g===id ? '' : g;
        }

        /** Bundled mods, gathered under whatever ships them, biggest group first. */
        function bundlesOf(list){
          const by={};
          for(const m of list){
            const g=groupOf(m);
            if(!g) continue;
            (by[g]=by[g]||[]).push(m);
          }
          return Object.keys(by).sort((a,b)=>by[b].length-by[a].length).map(k=>({
            parent:k, mods:by[k].sort((a,b)=>a.id.localeCompare(b.id))}));
        }

        /**
         * The bundled mods, folded away.
         *
         * <p>Fabric API alone is forty entries, and a client running six mods
         * looked like a client running fifty. They are still here — a
         * restricted mod bundled inside another one is exactly the thing you
         * would want to find — but they are one line until asked for.
         */
        function paintBundles(box,groups,at){
          if(!box) return;
          box.innerHTML='';
          for(const g of groups){
            const wrap=document.createElement('div');
            wrap.style.margin='6px 0 0';
            const flagged=g.mods.filter(m=>m.restricted).length;
            const head=document.createElement('button');
            head.className='btn';
            head.style.width='100%';
            head.style.textAlign='left';
            let open=flagged>0;      // never hide a restricted mod behind a fold
            const label=()=> (open?'▾ ':'▸ ')+'Bundled inside '+g.parent+
              ' ('+g.mods.length+')'+(flagged?' — '+flagged+' restricted':'');
            head.textContent=label();
            const list=document.createElement('div');
            const draw=()=>{ head.textContent=label();
              list.style.display=open?'':'none';
              if(open) paintMods(list,g.mods,at,false); else list.innerHTML=''; };
            head.onclick=()=>{ open=!open; draw(); };
            wrap.append(head,list);
            box.appendChild(wrap);
            draw();
          }
        }

        /**
         * One list of client mods: new ones marked, removed ones dated.
         *
         * <p>Both dates are shown rather than one relative age. "since 4d 2h
         * ago" answers a question nobody asked; "since 12 Aug" is the one that
         * lines up with when something started going wrong.
         */
        function paintMods(box,list,at,removed){
          if(!box) return;
          box.innerHTML='';
          for(const m of list){
            const row=document.createElement('div');
            // New means "arrived at the join that produced this report", which
            // is the only definition that does not call every mod new the
            // first time a client is seen.
            const fresh=!removed && m.firstSeen>=at-1000;
            const flag=clientReview && clientReview.by ? clientReview.by[m.id] : null;
            row.className='cmod'+(fresh?' fresh':'')+(removed?' gone':'')+
              (m.restricted?' banned':'')+
              (flag&&flag.level==='concern'?' banned':'');
            const when = removed
              ? 'gone '+(fmtWhen(m.removedAt)||fmtAgo(m.removedAt))
              : (fresh?'new':'since '+(fmtWhen(m.firstSeen)||fmtAgo(m.firstSeen)));
            row.innerHTML=(fresh?'<i class="plus">+</i>':(removed?'<i class="minus">−</i>':''))+
              '<code>'+esc(m.id)+'</code>'+
              (m.version?'<span class="ver">'+esc(m.version)+'</span>':'')+
              (m.restricted?'<span class="ban">restricted</span>':'')+
              (flag&&flag.level!=='fine'
                ? '<span class="ban" style="background:'+
                  (flag.level==='concern'?'rgba(255,90,110,.18)':'rgba(255,193,77,.16)')+
                  ';color:'+(flag.level==='concern'?'#ff9aa8':'#ffc14d')+'">'+
                  esc(flag.level==='unknown'?'unrecognised':flag.level)+'</span>'
                : '')+
              '<span class="when">'+esc(when)+'</span>';
            const title=[];
            if(!removed && m.firstSeen) title.push('first seen '+fmtAgo(m.firstSeen));
            if(removed && m.removedAt) title.push('last seen '+fmtAgo(m.removedAt));
            if(flag && flag.why) title.push(flag.why);
            if(title.length) row.title=title.join(' · ');
            box.appendChild(row);
          }
        }

        // ---- a second opinion on a mod list ----
        let clientReview=null;

        /**
         * Asks the model what one client's mods are.
         *
         * <p>Never automatic, and never part of loading the page. Pointing a
         * language model at a named person's computer is a decision, so it is
         * a button somebody presses.
         */
        async function reviewMods(p,c){
          const btn=$('cl-ask'), box=$('cl-review');
          if(!box) return;
          btn.disabled=true; btn.textContent='Reading…';
          box.innerHTML='<div class="note">asking the model…</div>';
          const r=await jpost('/api/client/review',{uuid:p.uuid});
          btn.disabled=false; btn.textContent='Ask again';
          if(r.status!==200){
            box.innerHTML='<div class="msg err">'+
              esc((r.body&&(r.body.error||r.body.message))||'failed')+'</div>';
            return;
          }
          const flags=r.body.flags||[];
          clientReview={by:{}};
          for(const f of flags) clientReview.by[f.id]=f;
          const notable=flags.filter(f=>f.level==='concern'||f.level==='watch');
          box.innerHTML=
            (r.body.summary?'<div class="summary">'+esc(r.body.summary)+'</div>':'')+
            (notable.length
              ? '<div id="cl-flags" style="margin-top:8px"></div>'
              : '<div class="note" style="margin-top:8px">Nothing on the list stood out to '+
                'the model.</div>')+
            '<p class="muted" style="margin:8px 0 10px;font-size:11.5px">Written by a model '+
            'from a list the client sent about itself. A modified client can report anything, '+
            'and a model can be wrong about what a mod does \u2014 so this is somewhere to '+
            'start looking, and it is not evidence of anything.</p>';
          const list=$('cl-flags');
          if(list){
            for(const f of notable){
              const row=document.createElement('div');
              row.className='moment';
              row.innerHTML='<span class="lb">'+esc(f.id)+'</span>'+
                '<span class="muted">'+esc(f.level==='concern'?'an advantage':'depends on '+
                  'your rules')+'</span>'+
                (f.why?'<span class="wy">'+esc(f.why)+'</span>':'');
              list.appendChild(row);
            }
          }
          // The rows themselves now carry the flag, so redraw them.
          const own=(c.mods||[]).filter(m=>!groupOf(m));
          paintMods($('cl-mods'),own,c.at,false);
          paintBundles($('cl-bundled'),bundlesOf(c.mods||[]),c.at);
          if((c.removed||[]).length) paintMods($('cl-gone'),c.removed,c.at,true);
        }

        async function sendMask(name,mask,clear){
          const r=await jpost('/api/mask',{name:name,mask:mask,clear:clear});
          const msg=$('p-msg'); msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200?(r.body.message||'done'):(r.body.error||'failed');
          loadPlayers();
        }

        // ---- player activity ----
        // Brighter than the list they replace: these sit on grass, sand, stone
        // and water, and a colour that reads against one of those is not
        // enough. Every one of them is also a different shape, so the map does
        // not depend on telling two hues apart.
        const ACTION_COLOR = { chat:'#8fd8ff', command:'#ffc14d', container:'#d6a8ff',
                               death:'#ff6b6b', attack:'#ff9a5e', hurt:'#ffbc8f',
                               join:'#6df06d', leave:'#aab4c2', respawn:'#a8f5a8',
                               item:'#ffe066', interact:'#b7a4ff', use:'#a3b0c2',
                               place:'#6fe6bd', 'break':'#ffc55e', afk:'#9aa5b4',
                               kill:'#ff3d6e', craft:'#c6f24e', trade:'#4fd6d6',
                               drop:'#cfd6e0', sleep:'#7b8cff', portal:'#ff7ad9',
                               advancement:'#ffef8f', enchant:'#b98cff', sign:'#e0c08a' };
        function activityPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">What players have been doing. '+
            'Rows are deleted once they pass the retention window.</p>'+
            '<section><div class="bartitle"><h2>Everyone, over time</h2>'+
            '<span class="spacer"></span>'+
            '<span class="mapchoice" id="t-map-choice"></span>'+
            '<span class="muted num" id="t-at"></span></div>'+
            '<div class="maplayout" id="t-layout">'+
              '<div>'+
                '<div id="t-map"><div class="note">loading…</div></div>'+
                '<div class="timeline" id="t-line"></div>'+
                '<div class="tlbar">'+
                  '<span class="live" id="t-livepill"><i></i>LIVE</span>'+
                  '<button class="btn go" id="t-play">Play</button>'+
                  '<span class="speed" id="t-speed"></span>'+
                  '<span class="muted num" id="t-rate"></span>'+
                  '<button class="btn" id="t-filter">Filter</button>'+
                  '<button class="btn" id="t-skip">Skip quiet time</button>'+
                  '<button class="btn" id="t-golive">Back to live</button>'+
                  '<span class="spacer"></span>'+
                  '<span class="dims" id="t-dims"></span>'+
                '</div>'+
                '<div id="t-filters"></div>'+
                '<div class="legend" id="t-legend"></div>'+
              '</div>'+
              '<aside class="mapside" id="t-side"></aside>'+
            '</div></section>'+
            '<section id="t-insights">'+
              '<div class="bartitle"><h2>What happened</h2>'+
              '<span class="spacer"></span>'+
              '<span class="dims" id="i-scope"></span>'+
              '<button class="btn" id="i-run">Summarise</button></div>'+
              '<div class="term" id="i-askbar" style="margin:2px 0 10px">'+
                '<input id="i-ask" placeholder="what are you looking for? ' +
                  'e.g. anyone digging near spawn last night">'+
                '<button class="btn" id="i-askgo">Find</button>'+
                '<button class="btn" id="i-askclear">Clear</button></div>'+
              '<div id="i-asked"></div>'+
              '<div id="i-ai"></div>'+
              '<div id="i-found"></div>'+
              '<div id="i-eps"><div class="note">reading the log…</div></div>'+
            '</section>'+
            '<div id="a-admins" class="note" style="margin:12px 0"></div>'+
            '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px">'+
            '<input id="a-filter" placeholder="filter by player, action, detail or place" '+
            'style="flex:1;min-width:200px">'+
            '<button class="btn" id="a-refresh">Refresh</button>'+
            '<button class="btn danger" id="a-clear">Start again\u2026</button></div>'+
            '<div id="a-meta" class="muted" style="margin-bottom:8px"></div>'+
            '<div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">'+
            '<span class="muted">One player’s path</span>'+
            '<select id="a-who" style="min-width:170px;width:auto"></select>'+
            '<span class="muted" id="a-dims"></span></div>'+
            '<div id="a-map"></div>'+
            '<div class="act" id="a-rows" style="margin-top:12px"><div class="note">loading…</div></div>'+
            '<div class="msg" id="a-msg"></div>';
          setTimeout(()=>{
            loadActivity(); loadTrackList(); loadBlueMapStatus(); loadAll(); loadInsights();
            $('a-refresh').onclick=()=>{ loadActivity(); loadAll(); loadInsights();
              loadBlueMapStatus(true); loadTrack($('a-who').value); };
            $('t-play').onclick=togglePlay;
            $('t-skip').onclick=()=>{ skipGaps=!skipGaps; paintAll(); };
            $('t-golive').onclick=goLive;
            $('t-filter').onclick=()=>{ filterOpen=!filterOpen; paintFilters(); paintAll(); };
            $('t-livepill').onclick=()=>{ $('t-line').scrollIntoView({block:'nearest'}); };
            $('i-run').onclick=()=>runSummary(true);
            $('i-askgo').onclick=runAsk;
            $('i-askclear').onclick=()=>{ $('i-ask').value=''; asked=null;
              clearFilter(); paintAsked(); paintFilters(); paintAll(); paintInsights(); };
            $('i-ask').onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); runAsk(); } };
            $('a-clear').onclick=clearActivity;
            // Filtering is client-side over the rows already fetched, so
            // typing here asks the server for nothing.
            $('a-filter').oninput=paintActivity;
            $('a-who').onchange=()=>loadTrack($('a-who').value);
          },0);
          return wrap;
        }

        """;

    /**
     * The map: everyone on one clock, and what the marks on it meant.
     *
     * <p>Its own piece for the same reason as the others — the 64KB ceiling on
     * one string constant — and it is the piece that grows, so it starts on
     * its own rather than being cut out of PART2 again later.
     */
    private static final String PARTMAP = """
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

        // Live is where the map starts, because "what is happening" is the
        // question you open it with; "what happened at four o'clock" is the
        // one you come to second. Touching the timeline drops out of it, and
        // the playback controls only appear once you have.
        let live=true;

        /**
         * How the map is drawn, as opposed to what it draws.
         *
         * <p>Kept here and in localStorage rather than in config.json: these
         * are about one person's eyes on one screen, not about the server, and
         * two admins looking at the same map are allowed to disagree about how
         * dark the ground should be.
         */
        const MAP_DEFAULTS={dim:0.38, path:2.6, mark:2.2, head:1.0, colour:'action',
                            faces:true, paths:true, cluster:true, overlays:true,
                            sequences:true, refresh:10, v:3, sceneGround:true,
                            sceneGrid:true, sceneEvents:false, grid:true,
                            // Off by default: the map's job is to show what
                            // happened, and something that quietly removes
                            // things should be asked for rather than assumed.
                            fade:{on:false, minutes:120, cats:['world','fight','things']}};
        let mapOpts=Object.assign({},MAP_DEFAULTS);
        let optsOpen=false;
        // The map, given the whole window, with everything else floating over
        // it. Not the browser's own fullscreen — that swallows the tab strip
        // and the escape key belongs to it; this is the panel's, and Escape
        // leaves it.
        let fullMap=false;
        try {
          const saved=localStorage.getItem('almin.map');
          if(saved){
            const was=JSON.parse(saved);
            mapOpts=Object.assign({},MAP_DEFAULTS,was);
            // Nested, so a shallow merge would hand back a half-built object
            // to anything reading a key the saved copy predates.
            mapOpts.fade=Object.assign({},MAP_DEFAULTS.fade,was.fade||{});
            if(!Array.isArray(mapOpts.fade.cats)) mapOpts.fade.cats=MAP_DEFAULTS.fade.cats;
            // A remembered preference is normally the last word, but a default
            // that was simply wrong is not a preference — anyone who never
            // touched the slider is holding a number nobody chose.
            if(!(was.v>=2)){ mapOpts.head=MAP_DEFAULTS.head; mapOpts.v=2; }
            if(!(was.v>=3)){
              mapOpts.sceneGrid=MAP_DEFAULTS.sceneGrid;
              mapOpts.sceneEvents=MAP_DEFAULTS.sceneEvents;
              mapOpts.v=3;
            }
          }
        } catch(e){ /* private mode, or someone edited it by hand */ }
        function saveMapOpts(){
          try { localStorage.setItem('almin.map',JSON.stringify(mapOpts)); }
          catch(e){ /* not being able to remember is not a reason to fail */ }
        }
        // A minute of recorded time per second: real time is available but
        // watching a day at 1x would take a day.
        let playSpeed=60, skipGaps=true;
        let focusPlayer='';

        // Playback speed is a real multiple of recorded time: at 1x a second
        // on screen is a second that was lived. It used to mean "the visible
        // window, whatever its length, in twenty seconds", so the number on
        // the button described nothing — on a ten-minute period 1x ran at
        // thirty times real speed, and on a day it ran at four thousand.
        const FRAME_MS=50;
        const SPEEDS=[1,10,60,300,1800];
        let lastTick=0;

        // Nothing happening for this long is a gap worth marking and worth
        // skipping — shorter than this is just a quiet minute.
        const GAP_MS=90*1000;

        // How far back from the cursor a marker still counts as "just now".
        // A fraction of the period, so a busy hour and a quiet day both read.
        const MARKER_WINDOW=0.12;

        // Markers were sized for a map you looked at whole. Now that it zooms,
        // they are the thing you are looking for, so they are drawn larger.
        const MARK_SCALE=2.2;

        /**
         * What is being shown, when that is not everything.
         *
         * <p>Three independent restrictions, because they answer different
         * questions: which kinds of thing ("show me breaks"), which particular
         * thing ("show me breaks of Oak Log"), and which kind of stretch
         * ("show me the fights"). Empty means no restriction, so the common
         * case costs nothing and the control starts out saying "everything".
         */
        let filt={acts:new Set(), items:new Set(), kinds:new Set()};
        let filterOpen=false, filterOpenAct='';

        const CATEGORIES=[
          {key:'world',  name:'The world', acts:['place','break','use','sign']},
          {key:'fight',  name:'Fighting', acts:['attack','hurt','death','kill']},
          {key:'talk',   name:'Talking', acts:['chat','command']},
          {key:'move',   name:'Coming and going',
           acts:['join','leave','respawn','afk','mask','portal','sleep']},
          {key:'things', name:'Things',
           acts:['item','interact','container','craft','trade','drop']},
          {key:'marks',  name:'Milestones', acts:['advancement','enchant']}];

        /** Which group an action belongs to, for anything that works by group. */
        const ACT_CATEGORY={};
        for(const c of CATEGORIES) for(const a of c.acts) ACT_CATEGORY[a]=c.key;

        /** Actions that have a thing attached worth listing one by one. */
        const DETAILED=new Set(['place','break','use','attack','interact','item',
                                'kill','craft','trade','drop','sign','advancement']);

        /**
         * How visible something of a given age should be, or zero for gone.
         *
         * <p>Two different fades. The one that has always been there is about
         * recency — what happened just now stands out, everything else stays
         * legible — and its floor is high on purpose, because on a long period
         * almost everything is old and fading those away would empty the map
         * of the marks it exists to show.
         *
         * <p>The one this adds is about forgetting: past its window a thing is
         * not drawn at all. That is a different question and it is off unless
         * asked for, per group, because "stop showing me week-old chat" and
         * "stop showing me week-old block edits" are separate wishes.
         */
        function ageOpacity(category,age,windowMs){
          const f=mapOpts.fade;
          if(f && f.on && f.cats.indexOf(category)>=0){
            const limit=Math.max(1,f.minutes)*60000;
            const left=1-age/limit;
            if(left<=0) return 0;
            // Never quite to nothing before it goes: a mark at two percent is
            // a mark nobody can see that still crowds the one next to it.
            return 0.14+0.86*left;
          }
          const k=Math.min(1,age/Math.max(1,windowMs));
          return Math.max(0.55,0.98-k*0.43);
        }

        // A single SVG or BlueMap line has one opacity, so split a travelled
        // track into a small number of age bands. This puts movement on the
        // same clock as its icons without turning every saved sample into its
        // own DOM node or BlueMap marker. The older end decides a segment's
        // visibility, which prevents a fresh point after a long gap from
        // pulling an already-faded trail back onto the map.
        const TRACK_FADE_STEPS=12;
        function fadedTrackRuns(points,cursor,windowMs){
          const runs=[]; let run=null;
          for(let i=1;i<points.length;i++){
            const a=points[i-1], b=points[i];
            const aa=Number(a.at), ba=Number(b.at);
            const oldest=Math.min(Number.isFinite(aa)?aa:cursor,
                                  Number.isFinite(ba)?ba:cursor);
            const raw=ageOpacity('move',Math.max(0,cursor-oldest),windowMs);
            if(!(raw>0)){ run=null; continue; }
            const opacity=Math.max(1,Math.min(TRACK_FADE_STEPS,
              Math.round(raw*TRACK_FADE_STEPS)))/TRACK_FADE_STEPS;
            if(run&&run.opacity===opacity&&run.last===a){
              run.points.push(b); run.last=b;
            } else {
              run={opacity:opacity,points:[a,b],last:b}; runs.push(run);
            }
          }
          return runs;
        }

        // During playback the faint guide is only the path still ahead. If it
        // included the travelled portion too, an expired trail would remain
        // visible underneath its faded segments.
        function futureTrackPoints(points,cursor){
          const first=points.findIndex(p=>Number(p.at)>cursor);
          return first<0?[]:points.slice(Math.max(0,first-1));
        }

        function filtering(){
          return filt.acts.size>0 || filt.items.size>0 || filt.kinds.size>0;
        }
        function clearFilter(){
          filt={acts:new Set(), items:new Set(), kinds:new Set()};
        }

        /**
         * Whether one row survives the filter.
         *
         * <p>Ticking a kind shows that kind; ticking a particular thing under
         * it narrows to that thing. A kind with nothing ticked under it is not
         * narrowed, so "break" and "break: Oak Log" mean what they look like
         * they mean.
         */
        function passes(a){
          if(filt.acts.size && !filt.acts.has(a.action)) return false;
          if(filt.items.size){
            let narrowed=false;
            for(const key of filt.items){
              if(key.slice(0,key.indexOf('\u0000'))===a.action){ narrowed=true; break; }
            }
            if(narrowed && !filt.items.has(a.action+'\u0000'+(a.detail||''))) return false;
          }
          if(filt.kinds.size && !inChosenSequence(a)) return false;
          return true;
        }

        /**
         * Whether a row happened during a stretch of a chosen kind.
         *
         * <p>Episodes carry their player and their window, so this is the
         * honest reading of "show me the fights": everything that player did
         * while the fight was going on, rather than only the swings.
         */
        function inChosenSequence(a){
          for(const e of episodes){
            if(!filt.kinds.has(e.kind)) continue;
            if(e.player!==a.player) continue;
            if(a.at>=e.from-1000 && a.at<=e.to+1000) return true;
          }
          return false;
        }

        /** The filter panel, drawn under the map when it is open. */
        function paintFilters(){
          const host=$('t-filters'); if(!host) return;
          const button=$('t-filter');
          if(button){
            const n=filt.acts.size+filt.items.size+filt.kinds.size;
            button.className='btn'+(filtering()?' on':'');
            button.textContent=filtering()?'Filter ('+n+')':'Filter';
          }
          if(!filterOpen){ host.innerHTML=''; return; }

          const acts=(allData&&allData.actions)||[];
          const counts=new Map(), details=new Map();
          for(const a of acts){
            const n=Math.max(1,a.count||1);
            counts.set(a.action,(counts.get(a.action)||0)+n);
            if(!DETAILED.has(a.action) || !a.detail) continue;
            if(!details.has(a.action)) details.set(a.action,new Map());
            const d=details.get(a.action);
            d.set(a.detail,(d.get(a.detail)||0)+n);
          }
          const kinds=new Map();
          for(const e of episodes) kinds.set(e.kind,(kinds.get(e.kind)||0)+1);

          const box=document.createElement('div');
          box.className='filterbox';
          const head=document.createElement('div');
          head.className='fhead';
          head.innerHTML='<b>Show</b>'+
            (filtering()?'<span class="muted">everything ticked</span>'
                        :'<span class="muted">everything</span>');
          const clear=document.createElement('button');
          clear.className='btn'; clear.textContent='Everything';
          clear.disabled=!filtering();
          clear.onclick=()=>{ clearFilter(); paintFilters(); paintAll(); };
          head.appendChild(clear);
          const shut=document.createElement('button');
          shut.className='btn'; shut.textContent='Done';
          shut.onclick=()=>{ filterOpen=false; paintFilters(); };
          head.appendChild(shut);
          box.appendChild(head);

          const grid=document.createElement('div');
          grid.className='fgrid';
          for(const cat of CATEGORIES){
            const present=cat.acts.filter(a=>counts.has(a));
            if(!present.length) continue;
            const col=document.createElement('div');
            col.className='fcat';
            col.innerHTML='<h5>'+esc(cat.name)+'</h5>';
            for(const action of present){
              col.appendChild(filterRow(action,counts.get(action),details.get(action)));
            }
            grid.appendChild(col);
          }
          if(kinds.size){
            const col=document.createElement('div');
            col.className='fcat';
            col.innerHTML='<h5>Sequences</h5>';
            for(const [kind,n] of [...kinds.entries()].sort((a,b)=>b[1]-a[1])){
              const line=document.createElement('label');
              line.className='fline';
              const cb=document.createElement('input');
              cb.type='checkbox'; cb.checked=filt.kinds.has(kind);
              cb.onchange=()=>{ cb.checked?filt.kinds.add(kind):filt.kinds.delete(kind);
                paintFilters(); paintAll(); };
              const t=document.createElement('span');
              t.innerHTML='<svg viewBox="-11 -11 22 22" width="17" height="17">'+
                sequenceIcon(kind,0,0,SEQUENCE_COLOR[kind]||'#ffab33',1)+'</svg> '+esc(kind);
              const c=document.createElement('i'); c.textContent=n;
              line.append(cb,t,c);
              col.appendChild(line);
            }
            grid.appendChild(col);
          }
          box.appendChild(grid);
          host.innerHTML='';
          host.appendChild(box);
        }

        /** One action, with the things it was done to folded underneath it. */
        function filterRow(action,count,detailMap){
          const wrap=document.createElement('div');
          const line=document.createElement('label');
          line.className='fline';
          const cb=document.createElement('input');
          cb.type='checkbox'; cb.checked=filt.acts.has(action);
          cb.onchange=()=>{
            if(cb.checked) filt.acts.add(action);
            else {
              filt.acts.delete(action);
              // Unticking the kind drops the things under it, or the map would
              // go on hiding rows for a reason that is no longer on screen.
              for(const key of [...filt.items]) {
                if(key.slice(0,key.indexOf('\u0000'))===action) filt.items.delete(key);
              }
            }
            paintFilters(); paintAll();
          };
          const t=document.createElement('span');
          t.innerHTML='<svg viewBox="-11 -11 22 22" width="17" height="17">'+
            marker(action,0,0,ACTION_COLOR[action]||'#9aa3ae',1)+'</svg> '+esc(action);
          const c=document.createElement('i'); c.textContent=count;
          line.append(cb,t,c);
          wrap.appendChild(line);

          if(detailMap && detailMap.size>1){
            const more=document.createElement('button');
            more.className='fmore';
            more.textContent=(filterOpenAct===action?'▾ ':'▸ ')+detailMap.size+' kinds';
            more.onclick=()=>{ filterOpenAct=filterOpenAct===action?'':action; paintFilters(); };
            wrap.appendChild(more);
            if(filterOpenAct===action){
              const sub=document.createElement('div');
              sub.className='fsub';
              const rows=[...detailMap.entries()].sort((a,b)=>b[1]-a[1]).slice(0,40);
              for(const [detail,n] of rows){
                const key=action+'\u0000'+detail;
                const l=document.createElement('label');
                l.className='fline';
                const box=document.createElement('input');
                box.type='checkbox'; box.checked=filt.items.has(key);
                box.onchange=()=>{ box.checked?filt.items.add(key):filt.items.delete(key);
                  paintFilters(); paintAll(); };
                const label=document.createElement('span'); label.textContent=detail;
                const num=document.createElement('i'); num.textContent=n;
                l.append(box,label,num);
                sub.appendChild(l);
              }
              if(detailMap.size>40){
                sub.insertAdjacentHTML('beforeend',
                  '<div class="muted" style="font-size:11px;padding:3px 0">'+
                  (detailMap.size-40)+' more not listed</div>');
              }
              wrap.appendChild(sub);
            }
          }
          return wrap;
        }

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
        let shots=[], shotEvery=0, shotTextures='none';

        /**
         * Fetches the period.
         *
         * @param keep true to leave the framing alone — where the map is
         *             looking, which slice of the timeline is open, which
         *             player is focused. Live mode refreshes on a timer, and a
         *             refresh that reset the view every ten seconds would make
         *             the map impossible to look at.
         */
        let liveLoading=false;
        async function loadAll(keep){
          const box=$('t-map'); if(!box) return;
          if(liveLoading) return;
          liveLoading=true;
          try {
            const r=await jget('/api/track?all=1');
            if(r.status!==200){
              if(!keep) box.innerHTML='<div class="note">unavailable</div>';
              return;
            }
            const dim=allDim;
            allData=r.body;
            showAdmins(r.body.admins);
            const m=await jget('/api/map');
            shots=(m.status===200 && m.body.shots)?m.body.shots:[];
            shotEvery=(m.body&&m.body.every)||0;
            shotTextures=(m.body&&m.body.textures)||'none';
            loadBlockColours();
            if(keep){ allDim=dim; }
            else { allDim=''; cursorSet=false; win.set=false; view.set=false; }
            lastLiveLoad=Date.now();
            paintAll();
          } finally { liveLoading=false; }
        }

        // Live refreshes on its own clock rather than on the panel's three
        // seconds: the period is a couple of thousand rows, and asking for it
        // twenty times a minute would be rude to a server that is also running
        // a game. How often is a per-viewer preference, so it lives with the
        // rest of them in the panel beside the map.
        let lastLiveLoad=0, lastInsight=0;
        function liveTick(){
          if(tab!=='activity' || document.hidden) return;
          const every=Math.max(2,mapOpts.refresh||10)*1000;
          if(live && Date.now()-lastLiveLoad >= every) loadAll(true);
          if(Date.now()-lastBlueStatus>=20000) loadBlueMapStatus();
          // The episode list and whatever the model last said are part of the
          // same picture, and used to change only when the whole page was
          // reloaded. Slower than the map, because working out episodes costs
          // the server a pass over the log.
          if(Date.now()-lastInsight >= Math.max(every,20000)){
            lastInsight=Date.now();
            loadInsights();
          }
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
         * Every patch of ground anybody has a picture of, as it last looked.
         *
         * <p>Pictures are taken of wherever people are, and windows are
         * aligned to a grid, so a server that has been played on for a week
         * has pictures of a dozen different places rather than a dozen
         * pictures of one. Showing only the one that matches the cursor threw
         * all the others away: walk away from your base and the map went blank
         * behind you, when the ground there had not changed and Almin had a
         * perfectly good picture of it.
         *
         * <p>So: one picture per patch — the newest taken at or before the
         * cursor, and the earliest there is for a patch first seen later —
         * oldest first, so a fresher picture of the same patch draws on top.
         */
        function shotsFor(dim,at){
          const byPatch=new Map();
          for(const s of shots){
            if(s.dim!==dim) continue;
            const key=s.minX+'@'+s.minZ+'@'+s.span;
            const have=byPatch.get(key);
            if(!have){ byPatch.set(key,s); continue; }
            // Prefer the newest at or before the cursor; failing that, the
            // earliest there is, so a patch first seen later still shows.
            const haveOk=have.at<=at, sOk=s.at<=at;
            if(sOk && (!haveOk || s.at>have.at)) byPatch.set(key,s);
            else if(!sOk && !haveOk && s.at<have.at) byPatch.set(key,s);
          }
          return [...byPatch.values()].sort((a,b)=>a.at-b.at);
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
          // A dark disc under every mark. Without one the shapes were drawn
          // straight onto the terrain, and a thin bright outline over pixel
          // art reads as noise rather than as a symbol — which is exactly what
          // a busy map looked like.
          const halo='<circle cx="'+x+'" cy="'+y+'" r="8.4" fill="#0a0c10" '+
            'fill-opacity=".78"/><circle cx="'+x+'" cy="'+y+'" r="8.4" fill="none" '+
            'stroke="'+fill+'" stroke-opacity=".38" stroke-width="1.2"/>';
          const body=halo+markerShape(action,x,y,fill);
          if(r===1) return body;
          return '<g transform="translate('+x+' '+y+') scale('+r.toFixed(3)+') translate('+
            (-x)+' '+(-y)+')">'+body+'</g>';
        }
        function markerShape(action,x,y,fill){
          const c=fill, r=1;
          const sq=(k,f)=>'<rect x="'+(x-k)+'" y="'+(y-k)+'" width="'+(2*k)+'" height="'+(2*k)+
            '" fill="'+(f?c:'none')+'" stroke="'+(f?'#0a0c10':c)+'" stroke-width="2" rx="1"/>';
          const li=(x1,y1,x2,y2,w)=>'<line x1="'+(x+x1)+'" y1="'+(y+y1)+'" x2="'+(x+x2)+
            '" y2="'+(y+y2)+'" stroke="'+c+'" stroke-width="'+(w||2.2)+'" stroke-linecap="round"/>';
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
            case 'interact':  return dot(3.6)+'<circle cx="'+x+'" cy="'+y+'" r="6" fill="none" '+
                                     'stroke="'+c+'" stroke-width="1.4"/>';
            // Stopped: a pause, inside the ring that means "still here".
            case 'afk':       return '<circle cx="'+x+'" cy="'+y+'" r="6" fill="none" stroke="'+c+
                                     '" stroke-width="1.5" stroke-dasharray="2.6 2.2"/>'+
                                     li(-1.8,-3,-1.8,3,2)+li(1.8,-3,1.8,3,2);
            // Something killed: crossed bones behind a skull. Deliberately
            // close to 'attack', which is crossed swords — one is a swing and
            // the other is the swing that landed.
            case 'kill':      return li(-6,-6,6,6,1.5)+li(-6,6,6,-6,1.5)+dot(4.2);
            // A crafting grid: a square divided in four.
            case 'craft':     return sq(5,false)+li(0,-5,0,5,1.3)+li(-5,0,5,0,1.3);
            // Two arrows passing each other.
            case 'trade':     return li(-6,-2.6,5,-2.6,1.8)+li(2.4,-5,5.4,-2.6,1.8)+
                                     li(2.4,-0.2,5.4,-2.6,1.8)+li(6,2.6,-5,2.6,1.8)+
                                     li(-2.4,0.2,-5.4,2.6,1.8)+li(-2.4,5,-5.4,2.6,1.8);
            // Something put down: an arrow onto the ground.
            case 'drop':      return li(0,-6,0,2,2)+li(-3,-1,0,2,2)+li(3,-1,0,2,2)+
                                     li(-5,5.4,5,5.4,2);
            // Asleep.
            case 'sleep':     return li(-4,-4.4,4,-4.4,2)+li(4,-4.4,-4,4.4,2)+li(-4,4.4,4,4.4,2);
            // A portal: a doorway that is taller than it is wide.
            case 'portal':    return '<ellipse cx="'+x+'" cy="'+y+'" rx="4" ry="6.2" fill="none" '+
                                     'stroke="'+c+'" stroke-width="2"/>'+dot(1.5);
            case 'advancement': return poly([[0,-6.4],[1.7,-2],[6.4,-2],[2.7,0.9],[4,5.4],
                                             [0,2.7],[-4,5.4],[-2.7,0.9],[-6.4,-2],[-1.7,-2]],true);
            // A sparkle, four-pointed.
            case 'enchant':   return '<path d="M'+x+' '+(y-6.4)+'L'+(x+1.5)+' '+(y-1.5)+'L'+(x+6.4)+
                                     ' '+y+'L'+(x+1.5)+' '+(y+1.5)+'L'+x+' '+(y+6.4)+'L'+(x-1.5)+
                                     ' '+(y+1.5)+'L'+(x-6.4)+' '+y+'L'+(x-1.5)+' '+(y-1.5)+
                                     'Z" fill="'+c+'" stroke="#0b0d11" stroke-width="1"/>';
            // A sign on a post.
            case 'sign':      return '<rect x="'+(x-5.4)+'" y="'+(y-5.6)+'" width="10.8" '+
                                     'height="7" rx="1" fill="'+c+'" stroke="#0b0d11" '+
                                     'stroke-width="1.2"/>'+li(0,1.4,0,6,2);
            default:          return dot(4.8);
          }
        }


        """;

    /**
     * Sequence badges, the filter, and the rest of the map's furniture.
     *
     * <p>Another piece for the same reason as the others: one string constant
     * cannot exceed 64KB, and the map's own piece reached it again.
     */
    private static final String PARTSEQ = """
        // ---- sequences, as one mark each ----
        // A stretch of work is a thing that happened, and forty identical
        // marks are not a picture of it. One badge with the tool on it says
        // "somebody was digging here" from across the map.
        const SEQUENCE_COLOR={fight:'#ff6b6b', pvp:'#ff4d4f', death:'#ff9a9a',
          tree:'#7bd88f', shaft:'#8fd8ff', tunnel:'#8fd8ff', mine:'#a9b4c2',
          dig:'#c9a227', clear:'#ffab33', build:'#ffd479', farm:'#9ade7b',
          loot:'#d6a8ff', travel:'#9fd0ff', pace:'#ffc14d', busy:'#9aa3ae',
          about:'#7a8595',
          // The one that has to be seen from across the map.
          hazard:'#ff3b30', grind:'#ff5c8a', tower:'#ffd479', bridge:'#e0c08a',
          redstone:'#ff6b6b', craft:'#c6f24e', trade:'#4fd6d6', sign:'#e0c08a',
          camp:'#7b8cff', milestone:'#ffef8f', dump:'#cfd6e0', roam:'#9fd0ff',
          flight:'#8fd8ff'};

        /**
         * One tool, drawn.
         *
         * <p>Same reasoning as the action shapes: drawn rather than fetched,
         * because the panel has to work on a server with no way out to the
         * internet and an icon set would be another thing to ship and license.
         */
        function toolShape(tool,c){
          const li=(x1,y1,x2,y2,w)=>'<line x1="'+x1+'" y1="'+y1+'" x2="'+x2+'" y2="'+y2+
            '" stroke="'+c+'" stroke-width="'+(w||1.9)+'" stroke-linecap="round"/>';
          switch(tool){
            // A blade and a crossguard.
            case 'sword':   return li(-3.6,3.6,3.4,-3.4,2.2)+li(-4.6,4.6,-3.2,3.2,2.6)+
                                   li(-1.2,-4.6,1.4,-2,1.8);
            // A haft with a curved head.
            case 'pickaxe': return li(-4.2,4.2,3.4,-3.4,1.9)+
                                   '<path d="M-1 -4.6q4.4 -2.2 7.2 1.2" fill="none" stroke="'+c+
                                   '" stroke-width="1.9" stroke-linecap="round"/>';
            case 'axe':     return li(-4.2,4.2,2.4,-2.4,1.9)+
                                   '<path d="M1.2 -4.4q4.6 0.4 4.2 4.6q-3 0.6 -4.8 -1.6z" fill="'+c+
                                   '" stroke="'+c+'" stroke-width="1"/>';
            case 'shovel':  return li(-4.2,4.2,2.2,-2.2,1.9)+
                                   '<path d="M1.6 -4.8l3.6 3.6l-2 2l-3.6 -3.6z" fill="'+c+'"/>';
            case 'hoe':     return li(-4.2,4.2,2.6,-2.6,1.9)+li(1,-4.4,5.4,-4.4,2)+
                                   li(5.4,-4.4,5.4,-1.6,2);
            // A hammer: a haft and a heavy head.
            case 'hammer':  return li(-3.6,4.4,1.4,-0.6,1.9)+
                                   '<rect x="0.4" y="-5.4" width="6.4" height="4.4" rx="1" fill="'+
                                   c+'"/>';
            case 'chest':   return '<rect x="-5" y="-3.6" width="10" height="7.6" rx="1" fill="'+c+
                                   '"/><path d="M-5 -0.6h10" stroke="#0b0d11" stroke-width="1.3"/>'+
                                   '<rect x="-1.1" y="-1.8" width="2.2" height="3" fill="#0b0d11"/>';
            // A boot, centred on the badge. It used to be drawn from a
            // corner, so it hung to the left of the circle it sits in and
            // read as a chevron pointing nowhere.
            case 'boots':   return '<path d="M-1.2 -5v6.6h4.4v3.4h-6.4v-10z" fill="'+c+
                                   '" transform="translate(1 0.6)"/>';
            case 'loop':    return '<path d="M4.4 -1.2a4.8 4.8 0 1 1 -1.6 -3.2" fill="none" '+
                                   'stroke="'+c+'" stroke-width="2" stroke-linecap="round"/>'+
                                   '<path d="M2 -5.4l1.6 1.4l-1.8 1.4z" fill="'+c+'"/>';
            case 'skull':   return '<circle cx="0" cy="-0.6" r="4.4" fill="'+c+'"/>'+
                                   '<path d="M-2.2 4h4.4" stroke="'+c+'" stroke-width="2" '+
                                   'stroke-linecap="round"/>'+
                                   '<circle cx="-1.6" cy="-1" r="1.2" fill="#0b0d11"/>'+
                                   '<circle cx="1.6" cy="-1" r="1.2" fill="#0b0d11"/>';
            // A flame, for the handful of blocks that ruin an afternoon.
            case 'flame':   return '<path d="M0 -7.2q1.2 3 3.2 4.6a4.6 4.6 0 1 1 -6.4 0'+
                                   'q0.5 -0.7 1 -1.7q0.8 1.2 2 1.6q-0.7 -2.4 0.2 -4.5z" fill="'+
                                   c+'"/><path d="M0 -1.2q1.5 1.5 1.5 2.7a1.5 1.5 0 0 1 -3 0'+
                                   'q0 -1.2 1.5 -2.7z" fill="#0a0c10"/>';
            // A spark of redstone: a dot with legs.
            case 'spark':   return '<circle cx="0" cy="0" r="2.4" fill="'+c+'"/>'+
                                   li(0,-6,0,-3,1.7)+li(0,3,0,6,1.7)+li(-6,0,-3,0,1.7)+
                                   li(3,0,6,0,1.7);
            // A crafting grid.
            case 'grid':    return '<rect x="-5.4" y="-5.4" width="10.8" height="10.8" rx="1" '+
                                   'fill="none" stroke="'+c+'" stroke-width="1.6"/>'+
                                   li(0,-5.4,0,5.4,1.3)+li(-5.4,0,5.4,0,1.3);
            // A coin, for trading.
            case 'coin':    return '<circle cx="0" cy="0" r="5.2" fill="none" stroke="'+c+
                                   '" stroke-width="1.8"/>'+li(0,-2.6,0,2.6,1.6)+
                                   li(-2,-2.6,2,-2.6,1.5)+li(-2,2.6,2,2.6,1.5);
            // A sign on its post.
            case 'signpost': return '<rect x="-5.4" y="-5.8" width="10.8" height="7" rx="1" '+
                                   'fill="'+c+'"/>'+li(0,1.2,0,6,2);
            // A bed seen from the side.
            case 'bed':     return '<path d="M-6 3.2v-4.4h8a3 3 0 0 1 3 3v1.4z" fill="'+c+'"/>'+
                                   li(-6,3.6,6,3.6,1.6)+li(-4.4,-1.4,-4.4,-3.6,1.6);
            case 'star':    return '<path d="M0 -6.4L1.7 -2L6.4 -2L2.7 0.9L4 5.4L0 2.7L-4 5.4'+
                                   'L-2.7 0.9L-6.4 -2L-1.7 -2Z" fill="'+c+'"/>';
            // Something falling.
            case 'drop':    return li(0,-6,0,1.6,2.2)+li(-3.2,-1.6,0,1.6,2.2)+
                                   li(3.2,-1.6,0,1.6,2.2)+li(-5,5,5,5,2.2);
            // A compass needle, for wandering.
            case 'compass': return '<circle cx="0" cy="0" r="5.6" fill="none" stroke="'+c+
                                   '" stroke-width="1.6"/>'+
                                   '<path d="M0 -4.2L2 1L0 0L-2 1Z" fill="'+c+'"/>';
            // A wing, for anything moving faster than a person can run.
            case 'wing':    return '<path d="M-6 -3q6 -1.4 11 4.4q-6 1.4 -11 -4.4z" fill="'+c+
                                   '"/>'+li(-6,-3,-6,4,1.8);
            default:        return '<circle cx="0" cy="0" r="3.6" fill="'+c+'"/>';
          }
        }

        /**
         * The game's own item texture for a tool, where this server has any.
         *
         * <p>An iron pickaxe drawn by Mojang beats one drawn by me, and the
         * textures are already here for the ground. Falls back to the drawn
         * shape, which is what a server with no resource pack gets.
         */
        const TOOL_ITEM={pickaxe:'iron_pickaxe', axe:'iron_axe', shovel:'iron_shovel',
          hoe:'iron_hoe', sword:'iron_sword', hammer:'iron_axe', chest:'', boots:'iron_boots',
          loop:'', skull:'', flame:'flint_and_steel', spark:'redstone', grid:'crafting_table',
          coin:'emerald', signpost:'oak_sign', bed:'red_bed', star:'', drop:'',
          compass:'compass', wing:'elytra'};
        let toolTextures=false;

        /** A sequence badge: the tool on a dark disc, centred on (x, y). */
        function sequenceIcon(kind,x,y,fill,scale){
          const r=scale||1;
          const tool=SEQUENCE_TOOL[kind]||kind;
          const item=toolTextures?TOOL_ITEM[tool]:'';
          const face=item
            ? '<image href="/api/item?name='+encodeURIComponent(item)+
              '" x="-7" y="-7" width="14" height="14" style="image-rendering:pixelated"/>'
            : toolShape(tool,fill);
          const body='<circle cx="0" cy="0" r="9.6" fill="#0a0c10" fill-opacity=".88"/>'+
            '<circle cx="0" cy="0" r="9.6" fill="none" stroke="'+fill+'" stroke-width="1.6"/>'+
            face;
          return '<g transform="translate('+x+' '+y+') scale('+r.toFixed(3)+')">'+body+'</g>';
        }

        /** Which tool stands for a kind, when the server has not said. */
        const SEQUENCE_TOOL={fight:'sword', pvp:'sword', death:'skull', tree:'axe',
          shaft:'pickaxe', tunnel:'pickaxe', mine:'pickaxe', dig:'pickaxe',
          clear:'shovel', build:'hammer', farm:'hoe', loot:'chest',
          travel:'boots', pace:'loop', busy:'chest', about:'loop',
          hazard:'flame', grind:'sword', tower:'hammer', bridge:'hammer',
          redstone:'spark', craft:'grid', trade:'coin', sign:'signpost',
          camp:'bed', milestone:'star', dump:'drop', roam:'compass', flight:'wing',
          // What the model found rather than what the rules did. A different
          // shape on purpose: it is a claim, not a count.
          found:'star'};

        function stopPlay(){
          if(playTimer){ clearInterval(playTimer); playTimer=null; }
          const b=$('t-play'); if(b){ b.textContent='Play'; b.className='btn go'; }
        }
        function togglePlay(){
          if(playTimer){ stopPlay(); return; }
          const b=$('t-play'); if(!b) return;
          live=false;
          b.textContent='Pause'; b.className='btn on';
          const gaps=quietGaps();
          // Advance by the time that actually passed, not by the interval we
          // asked for. Browsers throttle timers on a busy page and clamp them
          // hard in a background tab, so a fixed step per tick makes the
          // number on the button a guess — which is the whole complaint.
          lastTick=Date.now();
          playTimer=setInterval(()=>{
            if(!allData){ stopPlay(); return; }
            const now=Date.now();
            // Capped: coming back to a tab that was throttled for a minute
            // should resume, not leap an hour.
            const elapsed=Math.min(1000,Math.max(0,now-lastTick));
            lastTick=now;
            cursorAt += elapsed*playSpeed;
            cursorSet=true;
            // Nothing happened here and nobody is watching an empty map for a
            // real-time hour. Step over it, and let the timeline show why.
            if(skipGaps){
              const g=gapAt(cursorAt,gaps);
              if(g) cursorAt=g.to;
            }
            // Round and round the visible slice. Zoom the timeline to choose
            // what to watch; the loop follows it rather than the whole day.
            if(cursorAt>=win.to) cursorAt=win.from;
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

        /**
         * Marker sizes, corrected for how large the map is being drawn.
         *
         * <p>One is the size everything was drawn at when the map was a panel
         * on a page; fullscreen makes a viewBox unit about half again as big,
         * so without this a mark that read well in the panel filled a
         * building. Clamped, because a very small or very tall map should not
         * make the marks unreadable in the other direction.
         */
        let unitAdjust=1;
        const REFERENCE_PX_PER_UNIT=1.1;
        function measureUnit(box){
          const svg=box.querySelector('svg');
          if(!svg) return;
          const r=svg.getBoundingClientRect();
          if(!r.width || !r.height) return;
          const perUnit=Math.min(r.width/proj.W, r.height/proj.H);
          if(!perUnit) return;
          unitAdjust=Math.max(0.55,Math.min(1.6,REFERENCE_PX_PER_UNIT/perUnit));
        }
        function worldX(px){ return view.cx+((px-proj.anchorX)/proj.W)*proj.span; }
        function worldZ(py){ return view.cz+((py-proj.H/2)/proj.W)*proj.span; }

        let paintQueued=false;
        function schedulePaint(){
          if(paintQueued) return;
          paintQueued=true;
          requestAnimationFrame(()=>{ paintQueued=false; paintAll(); });
        }

        function paintAll(){
          // The summary's subject follows the map: focusing somebody or
          // zooming into a corner changes what "Summarise" would mean, and
          // the control has to say so at the moment it changes.
          paintScopeChips();
          const box=$('t-map'); if(!box || !allData) return;
          // How big a viewBox unit currently is on screen, measured from the
          // map that is still there. Markers are sized in viewBox units, so
          // without this they grow with the window — which is why everything
          // ballooned the moment the map went fullscreen. Taken before the
          // rebuild, because after it there is nothing to measure.
          measureUnit(box);
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
          // Somewhere there is a picture of counts too, not only somewhere
          // somebody did something: a dimension nobody has been to since the
          // log rolled over is still a dimension worth being able to look at.
          const dims=[...new Set(all.map(p=>p.dim).filter(Boolean)
            .concat(shots.map(sh=>sh.dim).filter(Boolean)))];
          if(!allDim || !dims.includes(allDim)) allDim=dims[0]||'';
          $('t-dims').innerHTML = dims.length>1
            ? '<span class="lbl">Dimension</span>'+
              dims.map(d=>'<button'+(d===allDim?' class="on"':'')+' data-tdim="'+esc(d)+'">'+
                esc(prettyDim(d))+'</button>').join('')
            : '<span class="lbl">'+esc(prettyDim(allDim))+'</span>';

          const from=allData.from||0;
          // The period is exactly what is saved, end to end. It used to run to
          // the clock, which on a server nobody had played on since yesterday
          // meant most of the timeline was empty and there was nothing to zoom
          // out to but blank.
          const to=allData.to||allData.now||from+1;
          if(live){ cursorAt=to; cursorSet=true; }
          else if(!cursorSet){ cursorAt=to; cursorSet=true; }
          cursorAt=Math.max(from,Math.min(to,cursorAt));
          const cursor=cursorAt;
          const windowMs=Math.max(1,(to-from)*MARKER_WINDOW);
          // Live, the cursor sits on the newest thing there is — which on a
          // quiet server is not now, and saying so is better than implying it.
          const stale=(allData.now||0)-to;
          $('t-at').textContent=live
            ? (stale>120000?'live · nothing since '+fmtAgo(to):'live')
            : fmtAgo(cursor)+(cursor>=to-1000?' (latest)':'');

          // Not below three sampling intervals: with samples a few minutes
          // apart — a thinned track, or a server with a long
          // activity-track-seconds — the gap to the last one exceeds the AFK
          // threshold for everybody all the time, and a map where nobody is
          // ever moving says nothing.
          const afkSecs=Math.max(allData.afkSeconds||0,(allData.trackSeconds||0)*3);

          // Who had actually gone by the cursor. A path ends where somebody
          // logged off, and a face left standing there says they are there —
          // which after an evening of people coming and going is a map full of
          // players who all went home hours ago.
          const away={};
          for(const a of acts){
            if(a.action!=='join' && a.action!=='leave') continue;
            if(a.at>cursor) continue;
            const seen=away[a.player];
            if(!seen || a.at>=seen.at) away[a.player]={at:a.at, gone:a.action==='leave'};
          }
          const inDim=p=>p.dim===allDim;
          const mine=a=>!focusPlayer || a.player===focusPlayer;
          // Everything that had happened by the cursor, not just the last
          // moment of it. A narrow window looks tidy and is useless: scrub to
          // a quiet minute and the map goes blank, which says nothing about
          // where anything happened. Age is carried by fading instead.
          const shownActs=acts.filter(a=>inDim(a) && a.at<=cursor && mine(a) && passes(a));
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

          // The timeline, filters and side list are shared by both renderers.
          // Once the optional BlueMap web app is connected, only this canvas
          // changes; the legacy SVG remains one click away and untouched.
          if(usingBlueMap()){
            paintBlueMap({tracks:tracks,acts:acts,ids:ids,online:online,names:names,
              shownActs:shownActs,shownNames:shownNames,away:away,afkSecs:afkSecs,
              cursor:cursor,windowMs:windowMs});
            return;
          }

          const W=1000, H=Math.round(W*0.60);
          const side=$('t-side'), layout=$('t-layout');
          const wide=layout ? layout.clientWidth>=900 : false;
          const sidebar=wide && mapOpts.overlays;
          if(layout) layout.className='maplayout'+(sidebar?' side':'')+(fullMap?' fullmap':'');
          if(side) side.style.display=sidebar?'':'none';
          // With a panel beside the map, dead centre is not the middle of what
          // you can see. Nudged left so the interesting half is not under it.
          const anchorX=sidebar?W*0.46:W/2;

          const span=Math.max(8,view.span);
          proj={W:W,H:H,span:span,anchorX:anchorX};
          const sx=v=>((v-view.cx)/span)*W+anchorX;
          const sz=v=>((v-view.cz)/span)*W+H/2;

          // Ground nobody has a picture of — outside the last snapshot, or a
          // chunk that was not loaded when it was taken — used to come out as
          // flat black squares scattered through the terrain, which read as
          // holes in the world rather than as gaps in the record.
          // Drawn far past the viewBox on purpose. The SVG is letterboxed to
          // keep the map square-on, and content is not clipped to the viewBox
          // — only to the viewport — so anything sized to the viewBox left
          // bare strips down each side. The ground picture reached into them
          // and the darkening rect did not, which is exactly the bright band
          // on either edge. Now the hatch fills them and everything that is
          // part of the map is clipped to the map.
          const backing='<defs><pattern id="unknown" width="12" height="12" '+
            'patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'+
            '<rect width="12" height="12" fill="#12161d"/>'+
            '<rect width="6" height="12" fill="#161b23"/></pattern>'+
            '<clipPath id="mapclip"><rect x="0" y="0" width="'+W+'" height="'+H+
            '"/></clipPath>'+
            // Drains the colour out of a face without hiding it.
            '<filter id="grey"><feColorMatrix type="saturate" values="0"/></filter>'+
            '</defs>'+
            '<rect x="'+(-2*W)+'" y="'+(-2*H)+'" width="'+(5*W)+'" height="'+(5*H)+
            '" fill="url(#unknown)"/>';
          // The ground as it was at the cursor. Nearest-neighbour scaling, so
          // it reads as blocks rather than as a blur.
          // Every patch there is a picture of, not only the one under the
          // cursor — and only the ones that would land on screen.
          const patches=[];
          for(const p of shotsFor(allDim,cursor)){
            const x0=sx(p.minX), z0=sz(p.minZ);
            const w=sx(p.minX+p.span)-x0, h=sz(p.minZ+p.span)-z0;
            if(x0+w<-4 || x0>W+4 || z0+h<-4 || z0>H+4) continue;
            // Addressed by the picture's own timestamp, not the cursor's:
            // during playback the cursor changes every frame, and that URL
            // would be a fresh request each time instead of a cache hit.
            patches.push('<image href="/api/map?at='+p.at+'&dim='+encodeURIComponent(allDim)+
              '" x="'+x0.toFixed(1)+'" y="'+z0.toFixed(1)+'" width="'+w.toFixed(1)+
              '" height="'+h.toFixed(1)+
              '" preserveAspectRatio="none" style="image-rendering:pixelated"/>');
          }
          const grid=mapOpts.grid?coordGrid(W,H,span,sx,sz):[];

          const groundImage=patches.length
            ? patches.join('')+
              // A thin scrim over the ground. The terrain is the background,
              // not the subject: muting it a little is what lets a path and a
              // handful of marks read as the thing on top of it.
              '<rect x="0" y="0" width="'+W+'" height="'+H+'" fill="#0a0c10" opacity="'+
              mapOpts.dim.toFixed(2)+'"/>'
            : '';

          const heads=[];
          const lines=shownNames.map(n=>{
            const c=playerColor(n);
            const full=tracks[n].filter(inDim);
            if(!full.length) return '';
            const upto=full.filter(p=>p.at<=cursor);
            const future=futureTrackPoints(full,cursor);
            const d=pts=>pts.map((p,i)=>(i?'L':'M')+sx(p.x).toFixed(1)+' '+
              sz(p.z).toFixed(1)).join(' ');
            // The path still ahead is faint, so you can see where to scrub.
            // Travelled segments use the same age curve as movement icons,
            // over a casing that fades with them rather than darkening old
            // ground after the coloured line has gone.
            const wide=mapOpts.path;
            let out=mapOpts.paths&&future.length>1
              ? '<path d="'+d(future)+'" fill="none" stroke="'+c+'" stroke-width="'+
                (wide*0.7).toFixed(1)+'" stroke-opacity=".16" stroke-linejoin="round" '+
                'stroke-linecap="round"/>'
              : '';
            if(upto.length){
              if(mapOpts.paths){
                const faded=fadedTrackRuns(upto,cursor,windowMs);
                out+=faded.map(r=>'<path d="'+d(r.points)+
                  '" fill="none" stroke="#0a0c10" stroke-width="'+
                  (wide+2.4).toFixed(1)+'" stroke-opacity="'+
                  Math.min(.45,r.opacity*.45).toFixed(2)+'" stroke-linejoin="round" '+
                  'stroke-linecap="round"/>').join('')+
                  faded.map(r=>'<path d="'+d(r.points)+'" fill="none" stroke="'+c+
                  '" stroke-width="'+wide.toFixed(1)+'" stroke-opacity="'+
                  Math.min(.98,r.opacity).toFixed(2)+'" stroke-linejoin="round" '+
                  'stroke-linecap="round"/>').join('');
              }
              // Where they were at the cursor, drawn as their own face —
              // square, because a Minecraft head is. The player's colour is
              // the frame around it, and stays visible if the face never
              // loads or is turned off.
              const last=upto[upto.length-1];
              // Nobody is sampled while they stand still, so the gap between
              // the cursor and their last sample is exactly how long they have
              // not moved — which is what AFK means, and it stays true when
              // you scrub back rather than only describing right now.
              const stillFor=cursor-last.at;
              const gone=!!(away[n] && away[n].gone && away[n].at>=last.at-1000);
              const idle=!gone && afkSecs>0 && stillFor>afkSecs*1000;
              const dim=gone||idle;
              const hx=sx(last.x), hy=sz(last.z);
              // Faces are sized on their own: they are what you look for on
              // the map, and tying them to the marker size meant making them
              // readable made everything else shout. Somebody who has left is
              // drawn smaller as well as greyer — where they went is still
              // worth knowing, and it is not worth as much as where the people
              // still here are.
              const R=13*mapOpts.head*unitAdjust*(gone?0.62:1);
              const frame=dim?'#5b6472':c;
              let head='<rect x="'+(hx-R).toFixed(1)+'" y="'+(hy-R).toFixed(1)+
                '" width="'+(R*2).toFixed(1)+'" height="'+(R*2).toFixed(1)+
                '" rx="'+(R*0.19).toFixed(1)+'" fill="'+frame+
                '" stroke="#0a0c10" stroke-width="'+(2.5*unitAdjust).toFixed(1)+'"/>';
              if(headsOn && mapOpts.faces && ids[n]){
                const inset=R*0.23;
                head+='<image href="/api/head?uuid='+encodeURIComponent(ids[n])+
                  '&name='+encodeURIComponent(n)+'" x="'+(hx-R+inset).toFixed(1)+
                  '" y="'+(hy-R+inset).toFixed(1)+'" width="'+((R-inset)*2).toFixed(1)+
                  '" height="'+((R-inset)*2).toFixed(1)+
                  '" style="image-rendering:pixelated"'+
                  // Greyed rather than hidden: where they are still matters,
                  // it is only that they are not doing anything there.
                  (dim?' filter="url(#grey)" opacity="'+(gone?'.55':'.72')+'"':'')+'/>';
              }
              if(gone){
                // A small tag on the corner, so a greyed face is read as
                // "left here" rather than as a face that failed to load.
                const tx=hx+R*0.72, ty=hy-R*0.72, tr=R*0.52;
                head+='<circle cx="'+tx.toFixed(1)+'" cy="'+ty.toFixed(1)+'" r="'+
                  tr.toFixed(1)+'" fill="#0a0c10" stroke="#9aa3ae" stroke-width="'+
                  (1.4*unitAdjust).toFixed(1)+'"/>'+
                  // The same left-pointing arrow the leave mark uses.
                  '<polygon points="'+
                  [[tr*0.42,-tr*0.5],[-tr*0.42,0],[tr*0.42,tr*0.5]]
                    .map(q=>(tx+q[0]).toFixed(1)+','+(ty+q[1]).toFixed(1)).join(' ')+
                  '" fill="#9aa3ae"/>';
              }
              // The state and the moment travel with the mark, so the hover
              // handler does not have to work them out again from data that
              // will have been rebuilt by the time anyone points at it.
              const when=gone?away[n].at:last.at;
              heads.push('<g class="thead'+(idle?' afk':'')+(gone?' gone':'')+
                '" data-who="'+esc(n)+'" data-state="'+(gone?'gone':idle?'afk':'here')+
                '" data-at="'+when+'" data-still="'+Math.round(stillFor/1000)+
                '" style="cursor:pointer">'+head+'<title>'+esc(n)+
                (gone?' — left here '+fmtAgo(away[n].at)
                     :(idle?' — not moving for '+humanSeconds(Math.round(stillFor/1000)):''))+
                '</title></g>');
            }
            return out;
          }).join('');

          // Work represented by an isometric badge is one thing on the map by
          // default, instead of the badge sitting on top of every block-place
          // row that made it. The button on the map can put those rows back.
          // A hidden, filtered, faded, or off-screen badge never swallows its
          // events: the replacement has to be visibly present first.
          const seqCandidates=[];
          if(mapOpts.sequences){
            for(const e of episodes){
              if(e.dim!==allDim) continue;
              if(focusPlayer && e.player!==focusPlayer) continue;
              if(filt.kinds.size && !filt.kinds.has(e.kind)) continue;
              if(e.from>cursor) continue;
              const ex=sx(e.x), ey=sz(e.z);
              if(ex<-40||ex>W+40||ey<-40||ey>H+40) continue;
              const fade=ageOpacity('seq',cursor-e.to,windowMs);
              if(fade<=0) continue;
              seqCandidates.push({e:e,x:ex,y:ey,fade:fade});
            }
          }
          const buildScenes=seqCandidates.map(q=>q.e).filter(e=>sceneKind(e)==='build');
          const scenePlaceKeys=new Set();
          for(const e of buildScenes){
            for(const key of scenePlaceKeysFor(e)) scenePlaceKeys.add(key);
          }
          const scenePlaces=scenePlaceKeys.size
            ? shownActs.filter(a=>a.action==='place' && scenePlaceKeys.has(sceneActionKey(a)))
            : [];
          const collapsedPlaces=mapOpts.sceneEvents?[]:scenePlaces;
          const hiddenPlaces=new Set(collapsedPlaces);
          const markActs=hiddenPlaces.size
            ? shownActs.filter(a=>!hiddenPlaces.has(a)) : shownActs;

          // Marks that land on the same patch of screen become one box with a
          // number on it. Binning is in screen pixels rather than in blocks,
          // which is what makes it a zoom control: the same evening is forty
          // separate marks close up and four boxes from far away, without
          // anything being hidden.
          const CELL=26;
          const bins=new Map();
          for(const a of markActs){
            const px=sx(a.x), py=sz(a.z);
            // Nothing off the edge of the map is drawn at all. With a couple
            // of thousand rows in hand that is most of the work skipped on a
            // zoomed-in view.
            if(px<-40||px>W+40||py<-40||py>H+40) continue;
            const key=Math.round(px/CELL)+':'+Math.round(py/CELL);
            let b=bins.get(key);
            if(!b){ b={sx:0,sy:0,items:[]}; bins.set(key,b); }
            b.items.push(a); b.sx+=px; b.sy+=py;
          }
          const drawn=[], groups=[], dotSvg=[], clusterSvg=[];
          const colourOf=a=>mapOpts.colour==='player'
            ? playerColor(a.player) : (ACTION_COLOR[a.action]||'#9aa3ae');
          const opacityOf=a=>ageOpacity(ACT_CATEGORY[a.action]||'things',
            cursor-a.at, windowMs);
          for(const b of bins.values()){
            if(!mapOpts.cluster || b.items.length<2){
              for(const a of b.items){
                const fade=opacityOf(a);
                if(fade<=0) continue;
                const age=Math.min(1,(cursor-a.at)/windowMs);
                dotSvg.push('<g class="tmk" data-i="'+drawn.length+'" opacity="'+
                  fade.toFixed(2)+'">'+
                  marker(a.action,+sx(a.x).toFixed(1),+sz(a.z).toFixed(1),
                    colourOf(a),mapOpts.mark*unitAdjust*(age>0.99?0.75:1))+'</g>');
                drawn.push(a);
              }
            } else {
              // A box is as visible as the freshest thing in it, and gone once
              // everything in it has gone.
              const live=b.items.filter(a=>opacityOf(a)>0);
              if(!live.length) continue;
              let fade=0;
              for(const a of live) fade=Math.max(fade,opacityOf(a));
              const cx=b.sx/b.items.length, cy=b.sy/b.items.length;
              clusterSvg.push('<g opacity="'+fade.toFixed(2)+'">'+
                clusterMark(cx,cy,live,groups.length,colourOf)+'</g>');
              groups.push({x:cx,y:cy,items:live});
            }
          }
          const dots=dotSvg.join('')+clusterSvg.join('');

          // One badge per stretch of work, over the marks that make it up. The
          // notable ones get their sentence beside them; labelling all of them
          // would bury the map under its own commentary.
          const seqs=[], seqShown=[];
          if(mapOpts.sequences){
            for(const q of seqCandidates){
              const e=q.e, ex=q.x, ey=q.y, fade=q.fade;
              const c=SEQUENCE_COLOR[e.kind]||'#ffab33';
              // The sentence waits for the pointer. Drawn always, a dozen of
              // them cover the map they are describing — and the badge already
              // says what kind of work it was, which is what a glance needs.
              seqs.push('<g class="tsq" data-i="'+seqShown.length+'" opacity="'+
                fade.toFixed(2)+'" style="cursor:pointer">'+
                sequenceIcon(e.kind,+ex.toFixed(1),+ey.toFixed(1),c,1.05*unitAdjust)+
                '</g>');
              seqShown.push(e);
            }
          }

          box.innerHTML='<div class="mapwrap">'+
            '<svg id="t-svg" viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="xMidYMid meet" '+
            'role="img" aria-label="Where everyone was and what they did">'+
            backing+'<g clip-path="url(#mapclip)">'+
            groundImage+grid.join('')+lines+dots+seqs.join('')+heads.join('')+'</g></svg>'+
            '<div class="maptip" id="t-tip"></div>'+
            (mapOpts.overlays?'<div class="onlinebar" id="t-online"></div>':'')+
            (scenePlaces.length?'<button class="sceneexpand" id="t-scene-events">'+
              (mapOpts.sceneEvents?'Collapse ':'Expand ')+scenePlaces.length+
              ' build event'+(scenePlaces.length===1?'':'s')+'</button>':'')+
            '<div class="mapbtns">'+
              '<button id="t-in" title="Zoom in">+</button>'+
              '<button id="t-out" title="Zoom out">−</button>'+
              '<button id="t-home" title="Fit everything in view">⌂</button>'+
              '<button id="t-full" title="'+(fullMap?'Leave fullscreen (Esc)':'Fullscreen')+
              '">'+(fullMap?'⤡':'⤢')+'</button>'+
              '<button id="t-cog" title="How the map looks">'+ICON.cog+'</button>'+
            '</div>'+
            (optsOpen?mapOptionsHtml():'')+
            '</div>';

          paintOnline(online,ids);
          paintLegend(shownNames,shownActs,span,shot);
          paintTimeline();
          paintSide(acts.filter(a=>mine(a) && passes(a)));
          wireMapGestures();
          wireMapButtons();
          const sceneEvents=$('t-scene-events');
          if(sceneEvents) sceneEvents.onclick=()=>{
            mapOpts.sceneEvents=!mapOpts.sceneEvents;
            saveMapOpts(); paintAll();
          };
          wireMarkers(box,drawn,W,H);
          wireClusters(box,groups);
          wireSequences(box,seqShown);
          wireMapOptions();
          wireDims();
          drawCluster();
          paintFilters();
        }

        """;

    /** The map controls, split from the renderer at the JVM's 64KB string limit. */
    private static final String PARTMAPUI = """

        /**
         * The panel of fine adjustments, beside the map rather than in the
         * settings tab.
         *
         * <p>These are the things you change while looking at the thing they
         * change — is the ground too bright to see the marks, is the path too
         * thin to follow — and a round trip to another tab to try a value is
         * how a setting ends up never being touched.
         */
        function mapOptionsHtml(){
          const range=(id,label,min,max,step,val)=>
            '<label><span>'+label+'</span><input type="range" id="'+id+'" min="'+min+
            '" max="'+max+'" step="'+step+'" value="'+val+'"></label>';
          const check=(id,label,on)=>
            '<label><span>'+label+'</span><input type="checkbox" id="'+id+'"'+
            (on?' checked':'')+'></label>';
          return '<div class="mapopts" id="t-opts">'+
            '<h4>How the map looks</h4>'+
            range('o-dim','Ground darkness',0,80,1,Math.round(mapOpts.dim*100))+
            range('o-path','Path width',1,7,0.5,mapOpts.path)+
            range('o-mark','Marker size',1,4,0.1,mapOpts.mark)+
            range('o-head','Face size',0.5,2.4,0.05,mapOpts.head)+
            '<label><span>Colour marks by</span><select id="o-colour">'+
              '<option value="action"'+(mapOpts.colour==='action'?' selected':'')+
              '>what it was</option>'+
              '<option value="player"'+(mapOpts.colour==='player'?' selected':'')+
              '>who did it</option>'+
            '</select></label>'+
            check('o-faces','Player faces',mapOpts.faces)+
            check('o-paths','Paths',mapOpts.paths)+
            check('o-cluster','Group crowded marks',mapOpts.cluster)+
            check('o-seq','Sequence badges',mapOpts.sequences)+
            check('o-grid','Coordinate grid',mapOpts.grid)+
            check('o-overlays','Side panel and player bar',mapOpts.overlays)+
            '<hr>'+
            '<label><span>Refresh every</span><input type="range" id="o-refresh" min="2" '+
            'max="120" step="1" value="'+(mapOpts.refresh||10)+'"></label>'+
            '<div class="onote" id="o-refreshnote"></div>'+
            '<hr>'+
            check('o-fade','Fade old marks away',mapOpts.fade.on)+
            (mapOpts.fade.on
              ? '<label><span>Gone after</span><input type="range" id="o-fademins" min="5" '+
                'max="10080" step="5" value="'+mapOpts.fade.minutes+'"></label>'+
                '<div class="onote" id="o-fadenote"></div>'+
                '<div class="chips" id="o-fadecats">'+
                  CATEGORIES.map(c=>'<button data-cat="'+c.key+'"'+
                    (mapOpts.fade.cats.indexOf(c.key)>=0?' class="on"':'')+'>'+
                    esc(c.name)+'</button>').join('')+
                  '<button data-cat="seq"'+
                    (mapOpts.fade.cats.indexOf('seq')>=0?' class="on"':'')+
                    '>Sequences</button>'+
                '</div>'
              : '')+
            '<div class="row"><button class="btn" id="o-reset">Reset</button>'+
            '<button class="btn" id="o-close">Done</button></div>'+
            '</div>';
        }

        /** "every 10 seconds", "gone after 2 hours" — the readouts under the sliders. */
        function humanMins(n){
          if(n<60) return n+(n===1?' minute':' minutes');
          if(n<1440){ const h=Math.round(n/6)/10;
            return h+(h===1?' hour':' hours'); }
          const d=Math.round(n/144)/10;
          return d+(d===1?' day':' days');
        }

        function wireMapOptions(){
          if(!optsOpen) return;
          const set=(id,ev,fn)=>{ const el=$(id); if(el) el[ev]=()=>{ fn(el); saveMapOpts();
            schedulePaint(); }; };
          set('o-dim','oninput',el=>mapOpts.dim=(+el.value)/100);
          set('o-path','oninput',el=>mapOpts.path=+el.value);
          set('o-mark','oninput',el=>mapOpts.mark=+el.value);
          set('o-head','oninput',el=>mapOpts.head=+el.value);
          set('o-colour','onchange',el=>mapOpts.colour=el.value);
          set('o-faces','onchange',el=>mapOpts.faces=el.checked);
          set('o-paths','onchange',el=>mapOpts.paths=el.checked);
          set('o-cluster','onchange',el=>mapOpts.cluster=el.checked);
          set('o-seq','onchange',el=>mapOpts.sequences=el.checked);
          set('o-grid','onchange',el=>mapOpts.grid=el.checked);
          set('o-overlays','onchange',el=>mapOpts.overlays=el.checked);
          set('o-refresh','oninput',el=>mapOpts.refresh=+el.value);
          set('o-fademins','oninput',el=>mapOpts.fade.minutes=+el.value);
          // Turning it on or off changes which controls are there, so this one
          // redraws the panel rather than only the map.
          const fadeBox=$('o-fade');
          if(fadeBox) fadeBox.onchange=()=>{ mapOpts.fade.on=fadeBox.checked;
            saveMapOpts(); paintAll(); };
          const chips=$('o-fadecats');
          if(chips) chips.querySelectorAll('button').forEach(b=>{
            b.onclick=()=>{
              const key=b.getAttribute('data-cat');
              const at=mapOpts.fade.cats.indexOf(key);
              if(at>=0) mapOpts.fade.cats.splice(at,1); else mapOpts.fade.cats.push(key);
              saveMapOpts(); paintAll();
            };
          });
          const rn=$('o-refreshnote');
          if(rn) rn.textContent='every '+(mapOpts.refresh||10)+' seconds';
          const fn=$('o-fadenote');
          if(fn) fn.textContent='gone after '+humanMins(mapOpts.fade.minutes);
          const reset=$('o-reset');
          if(reset) reset.onclick=()=>{
            mapOpts=Object.assign({},MAP_DEFAULTS);
            mapOpts.fade=Object.assign({},MAP_DEFAULTS.fade);
            mapOpts.fade.cats=MAP_DEFAULTS.fade.cats.slice();
            saveMapOpts(); paintAll();
          };
          const close=$('o-close');
          if(close) close.onclick=()=>{ optsOpen=false; paintAll(); };
        }

        /**
         * A crowd of marks, as one box with a number on it.
         *
         * <p>Sized by how much is inside, but only just — a box that grew with
         * its count would be a bar chart lying on a map, and the number is
         * already the number.
         */
        function clusterMark(x,y,items,index,colourOf){
          let total=0;
          const seen={};
          let best='', most=0;
          for(const a of items){
            const n=Math.max(1,a.count||1);
            total+=n;
            seen[a.action]=(seen[a.action]||0)+n;
            if(seen[a.action]>most){ most=seen[a.action]; best=a.action; }
          }
          const c=mapOpts.colour==='player'
            ? playerColor(items[0].player) : (ACTION_COLOR[best]||'#9aa3ae');
          const label=total>999?'999+':String(total);
          // Sized against the marks, not against the panel. A mark is a disc
          // of radius 8.4 scaled by the same factor, so a box that ignored it
          // came out a third the size of the things it stood in for.
          const k=mapOpts.mark*unitAdjust;
          const h=15*k;
          const w=Math.max(h,(5.2+label.length*5.4)*k);
          return '<g class="tcl" data-i="'+index+'" style="cursor:pointer">'+
            '<rect x="'+(x-w/2).toFixed(1)+'" y="'+(y-h/2).toFixed(1)+'" width="'+w.toFixed(1)+
            '" height="'+h.toFixed(1)+'" rx="'+(h/2.6).toFixed(1)+'" fill="#0a0c10" '+
            'fill-opacity=".86" stroke="'+c+'" stroke-width="'+(0.85*k).toFixed(1)+'"/>'+
            '<text x="'+x.toFixed(1)+'" y="'+(y+h*0.30).toFixed(1)+'" text-anchor="middle" '+
            'fill="'+c+'" font-size="'+(h*0.66).toFixed(1)+'" font-weight="700" '+
            'font-family="inherit">'+label+'</text>'+
            '<title>'+items.length+' entr'+(items.length===1?'y':'ies')+', '+total+
            ' in total — click to list them</title></g>';
        }

        /**
         * What is inside one of those boxes.
         *
         * <p>Identical things are folded with a count rather than repeated:
         * fifty rows of "broke Stone" is one line saying so, which is both
         * shorter and more informative than fifty lines.
         */
        function clusterList(items){
          const by=new Map();
          for(const a of items){
            const key=a.player+'\u0000'+a.action+'\u0000'+(a.detail||'');
            const have=by.get(key);
            const n=Math.max(1,a.count||1);
            if(have){ have.n+=n; have.at=Math.max(have.at,a.at); }
            else by.set(key,{a:a,n:n,at:a.at});
          }
          return [...by.values()].sort((p,q)=>q.n-p.n||q.at-p.at);
        }

        /**
         * The box that is currently open, in world coordinates.
         *
         * <p>Held here rather than as a DOM node because every repaint throws
         * the map away and builds it again — playback frames, live refreshes,
         * a pan — so a panel that only existed in the DOM was gone before it
         * could be read. Keeping where it is in blocks also means it stays on
         * its marks while you zoom.
         */
        let clusterAt=null;

        function closeCluster(){
          clusterAt=null;
          const box=$('t-cluster'); if(box) box.remove();
        }

        function wireClusters(box,groups){
          box.querySelectorAll('.tcl').forEach(el=>{
            const open=ev=>{
              ev.preventDefault(); ev.stopPropagation();
              const g=groups[+el.getAttribute('data-i')];
              if(!g) return;
              let x=0,z=0;
              for(const a of g.items){ x+=a.x; z+=a.z; }
              clusterAt={items:g.items, x:x/g.items.length, z:z/g.items.length};
              drawCluster();
            };
            // Both, because a mark is small and a click that moves a pixel
            // between down and up is a click the browser will not report.
            el.addEventListener('pointerup',open);
            el.addEventListener('click',open);
          });
          const svg=$('t-svg');
          if(svg && !svg.almCloses){
            svg.almCloses=true;
            svg.addEventListener('pointerdown',e=>{
              if(!e.target.closest('.tcl')) closeCluster();
            });
          }
        }

        /**
         * What is inside the open box, drawn where its marks are.
         *
         * <p>Called at the end of every paint, so it follows the map rather
         * than being orphaned by it.
         */
        function drawCluster(){
          const old=$('t-cluster'); if(old) old.remove();
          if(!clusterAt) return;
          const box=$('t-map'), svg=$('t-svg'); if(!box||!svg) return;
          const wrap=box.querySelector('.mapwrap'); if(!wrap) return;
          const r=svg.getBoundingClientRect(), b=wrap.getBoundingClientRect();
          // The SVG letterboxes inside its box; undo that or the panel lands
          // somewhere near the marks rather than on them.
          const scale=Math.min(r.width/proj.W,r.height/proj.H);
          if(!scale) return;
          const ox=(r.width-proj.W*scale)/2, oy=(r.height-proj.H*scale)/2;
          const gx=((clusterAt.x-view.cx)/proj.span)*proj.W+proj.anchorX;
          const gz=((clusterAt.z-view.cz)/proj.span)*proj.W+proj.H/2;

          const el=document.createElement('div');
          el.className='clusterbox';
          el.id='t-cluster';
          const rows=clusterList(clusterAt.items);
          let total=0;
          for(const row of rows) total+=row.n;
          const head=document.createElement('h4');
          head.textContent=total+' here · '+clusterAt.items[0].dim;
          const shut=document.createElement('button');
          shut.className='shut'; shut.textContent='×'; shut.title='Close';
          shut.onclick=ev=>{ ev.stopPropagation(); closeCluster(); };
          head.appendChild(shut);
          el.appendChild(head);
          for(const row of rows){
            const a=row.a;
            const line=document.createElement('div');
            line.className='cl';
            line.innerHTML='<span class="nm">'+esc(a.mask||a.player)+'</span>'+
              '<span style="color:'+(ACTION_COLOR[a.action]||'#9aa3ae')+'">'+esc(a.action)+
              '</span>'+(row.n>1?'<span class="xn">×'+row.n+'</span>':'')+
              (a.detail?'<span class="dt">'+esc(a.detail)+'</span>':'')+
              '<span class="tm">'+esc(fmtAgo(row.at).replace(' ago',''))+'</span>';
            line.onclick=ev=>{ ev.stopPropagation();
              cursorAt=row.at; cursorSet=true; live=false; stopPlay(); paintAll(); };
            el.appendChild(line);
          }
          wrap.appendChild(el);
          // Kept inside the map: a panel half off the right-hand edge is a
          // panel you cannot read.
          const px=r.left-b.left+ox+gx*scale, py=r.top-b.top+oy+gz*scale;
          const wide=el.offsetWidth||300, high=el.offsetHeight||200;
          el.style.left=Math.max(6,Math.min(wrap.clientWidth-wide-6,px+14))+'px';
          el.style.top=Math.max(6,Math.min(wrap.clientHeight-high-6,py-14))+'px';
        }

        /** The model's note about this stretch, if it made one. */
        function momentFor(e){
          if(!aiReport || !aiReport.moments) return null;
          for(const m of aiReport.moments){
            if(Math.abs(m.at-e.to)<=1000 && (!m.player||m.player===e.player)) return m;
          }
          return null;
        }

        /**
         * What the model thought this stretch was for.
         *
         * <p>Different from a moment: a moment says "look at this", and this
         * says "that was them clearing ground for the build next to it" —
         * which is the thing the episode's own sentence cannot know, because
         * it can only see the episode.
         */
        function meaningFor(e){
          if(!aiReport || !aiReport.sequences) return '';
          for(const m of aiReport.sequences){
            // The player too: two people can finish something in the same
            // second, and the wrong reading on the right row is worse than
            // no reading at all.
            if(Math.abs(m.at-e.to)>1000) continue;
            if(m.player && m.player!==e.player) continue;
            return m.means||'';
          }
          return '';
        }

        function wireSequences(box,shown){
          const svg=$('t-svg'), tip=$('t-tip');
          box.querySelectorAll('.tsq').forEach(el=>{
            el.onclick=ev=>{ ev.stopPropagation();
              const e=shown[+el.getAttribute('data-i')];
              if(!e) return;
              // A stretch with a shape opens as one; anything else takes the
              // map to it, which is all there is to show.
              if(hasShape(e)) openScene(e); else jumpTo(e.to,e.dim,e.x,e.z,e.y);
            };
            if(!svg||!tip) return;
            el.addEventListener('mouseenter',()=>{
              const e=shown[+el.getAttribute('data-i')];
              if(!e) return;
              const note=momentFor(e);
              const means=meaningFor(e);
              tip.textContent=(e.mask||e.player)+' · '+e.headline+
                (means?' — '+means:(note&&note.why?' — '+note.why:''))+
                ' · '+fmtAgo(e.to)+(hasShape(e)?' · click to see it':'');
              placeTip(tip,el,svg,box);
            });
            el.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          });
        }

        /** Puts the tooltip over one mark, wherever that mark is on screen. */
        function placeTip(tip,el,svg,box){
          const r=svg.getBoundingClientRect(), b=box.getBoundingClientRect();
          const g=el.getBBox();
          const scale=Math.min(r.width/proj.W,r.height/proj.H);
          const ox=(r.width-proj.W*scale)/2, oy=(r.height-proj.H*scale)/2;
          const x=r.left-b.left+ox+(g.x+g.width/2)*scale;
          const y=r.top-b.top+oy+g.y*scale-26;
          // Kept inside the map, or a mark near the edge points its label off
          // the side of the panel.
          tip.style.opacity='1';
          const wide=tip.offsetWidth||160;
          tip.style.left=Math.max(6,Math.min(box.clientWidth-wide-6,x-wide/2))+'px';
          tip.style.top=Math.max(4,y)+'px';
        }

        /**
         * Lines on round block coordinates, with the numbers on them.
         *
         * <p>The old grid was four lines at quarters of the screen, which meant
         * nothing — they moved when you panned and stood for no coordinate. A
         * map of a world people navigate by numbers should show the numbers,
         * so these sit on multiples of a round figure chosen for the zoom, and
         * are labelled where they meet the edges.
         */
        function coordGrid(W,H,span,sx,sz){
          const out=[];
          // Roughly one line every 130 pixels, rounded to something a person
          // would say out loud.
          const want=span*(130/W);
          const steps=[8,16,32,64,128,256,512,1024,2048,4096,8192];
          let step=steps[steps.length-1];
          for(const v of steps){ if(v>=want){ step=v; break; } }

          const left=worldX(0), right=worldX(W);
          const top=worldZ(0), bottom=worldZ(H);
          const line=(x1,y1,x2,y2,strong)=>
            '<line x1="'+x1.toFixed(1)+'" y1="'+y1.toFixed(1)+'" x2="'+x2.toFixed(1)+
            '" y2="'+y2.toFixed(1)+'" stroke="#dfe6ef" stroke-opacity="'+
            (strong?'.22':'.10')+'" stroke-width="'+(strong?1.2:0.8)+'"/>';
          const label=(text,x,y,anchor)=>
            '<text x="'+x.toFixed(1)+'" y="'+y.toFixed(1)+'" fill="#cbd3dd" '+
            'fill-opacity=".72" font-size="'+(11*unitAdjust).toFixed(1)+
            '" text-anchor="'+anchor+'" style="paint-order:stroke" stroke="#0a0c10" '+
            'stroke-width="'+(2.6*unitAdjust).toFixed(1)+'" stroke-opacity=".65">'+
            text+'</text>';

          for(let x=Math.ceil(left/step)*step;x<=right;x+=step){
            const px=sx(x);
            // The axis itself gets a brighter line: x=0 and z=0 are the two
            // coordinates everybody actually knows.
            out.push(line(px,0,px,H,x===0));
            out.push(label('x '+x,px+4,13*unitAdjust,'start'));
          }
          for(let z=Math.ceil(top/step)*step;z<=bottom;z+=step){
            const pz=sz(z);
            out.push(line(0,pz,W,pz,z===0));
            out.push(label('z '+z,4,pz-4,'start'));
          }
          return out;
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
            (shot?(shotTextures==='none'
              ? ' · <span title="Drop any resource pack into resourcepacks/ or '+
                'config/almin/textures.zip and the ground is drawn in the game\u2019s own '+
                'block textures instead">map palette</span>'
              : ' · textures from '+esc(shotTextures)):'')+
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
            row.onclick=()=>{ cursorAt=a.at; cursorSet=true; live=false; allDim=a.dim;
              view.cx=a.x; view.cz=a.z; view.set=true;
              if(usingBlueMap()) focusBlueMap(a.x,a.y,a.z,110);
              stopPlay(); paintAll(); };
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
          // Whatever the model thought was worth a look, on the strip that
          // covers the whole period — so "there was a fight at some point" is
          // answerable without reading anything.
          if(aiReport && aiReport.moments){
            for(const m of aiReport.moments){
              if(!m.at || m.at<from || m.at>to) continue;
              const x=ovx(m.at);
              sv+='<path d="M'+x.toFixed(1)+' 1L'+(x+4).toFixed(1)+' '+(OV/2)+'L'+
                x.toFixed(1)+' '+(OV-1)+'L'+(x-4).toFixed(1)+' '+(OV/2)+'Z" '+
                'fill="#ff8f90" stroke="#0b0d11" stroke-width="1"><title>'+
                esc(m.label)+'</title></path>';
            }
          }

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
            if(!passes(a)) continue;
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
          paintBar();
        }

        /**
         * The row under the timeline, which is two different rows.
         *
         * <p>Live, there is nothing to control — no speed, no direction, no
         * position — so the controls for those are not there, and the only
         * thing worth saying is that this is now. Touch the timeline and they
         * come back, because now there is something to control.
         */
        function paintBar(){
          const show=(id,on)=>{ const el=$(id); if(el) el.style.display=on?'':'none'; };
          show('t-livepill',live);
          show('t-play',!live);
          show('t-speed',!live);
          show('t-rate',!live);
          show('t-skip',!live);
          show('t-golive',!live);
          const skip=$('t-skip');
          if(skip){ skip.className='btn'+(skipGaps?' on':'');
            skip.title=skipGaps?'Playback jumps over time nobody was on'
                               :'Playback runs through quiet time in real proportion'; }
        }

        /** Back to following the clock. Also puts the whole period back in view. */
        function goLive(){
          live=true; stopPlay(); cursorSet=false; win.set=false;
          closeCluster();
          if(allData) paintAll();
          loadAll(true);
        }

        function paintSpeed(){
          const box=$('t-speed'); if(!box) return;
          box.innerHTML='';
          for(const s of SPEEDS){
            const b=document.createElement('button');
            b.textContent=s+'×';
            if(s===playSpeed) b.className='on';
            b.title='One second here is '+humanSeconds(s)+' of recorded time';
            b.onclick=()=>{ playSpeed=s; paintSpeed(); };
            box.appendChild(b);
          }
          const note=$('t-rate');
          if(note) note.textContent='1s = '+humanSeconds(playSpeed);
        }
        /** "45 seconds", "2 minutes", "1 hour" — for the speed readout. */
        function humanSeconds(n){
          if(n<60) return n+(n===1?' second':' seconds');
          if(n<3600){ const m=n/60; return m+(m===1?' minute':' minutes'); }
          const h=n/3600; return h+(h===1?' hour':' hours');
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
            // Touching the timeline is what "bring the timestamp back" means.
            cursorSet=true; live=false; stopPlay(); schedulePaint();
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
            if(e.target.closest('.tcl')||e.target.closest('.clusterbox')) return;
            if(e.target.closest('.mapbtns')||e.target.closest('.mapopts')) return;
            if(e.target.closest('.onlinebar')) return;
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
            // A panel drawn over the map has its own scrollbar, and taking the
            // wheel off it to zoom the map underneath is not what anyone
            // pointing at a list of forty rows meant.
            if(e.target.closest && (e.target.closest('.clusterbox')
              || e.target.closest('.mapopts'))) return;
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
          if(cog) cog.onclick=()=>{ optsOpen=!optsOpen; paintAll(); };
          const full=$('t-full');
          if(full) full.onclick=()=>setFull(!fullMap);
          const svg=$('t-svg'), tip=$('t-tip'), box=$('t-map');
          if(svg) svg.querySelectorAll('.thead').forEach(el=>{
            el.onclick=()=>{ const n=el.getAttribute('data-who');
              focusPlayer=focusPlayer===n?'':n; paintAll(); };
            if(!tip||!box) return;
            el.addEventListener('mouseenter',()=>{
              tip.textContent=headStory(el);
              placeTip(tip,el,svg,box);
            });
            el.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          });
        }

        /**
         * Hands the window to the map, or takes it back.
         *
         * <p>The layout does not change — the timeline and the side list are
         * the same elements in the same order — so nothing has to be rebuilt
         * or rewired. They are simply positioned over the map instead of
         * under and beside it, which is also why leaving fullscreen cannot
         * lose the frame you were looking at.
         */
        function setFull(on){
          if(fullMap===on) return;
          fullMap=on;
          document.body.style.overflow=on?'hidden':'';
          closeCluster();
          paintAll();
        }
        // Bound once, on the page, because in fullscreen the thing you want to
        // press Escape on is the whole window.
        if(!window.almEsc){
          window.almEsc=true;
          document.addEventListener('keydown',e=>{
            if(e.key!=='Escape') return;
            if(fullMap){ e.preventDefault(); setFull(false); }
            else if(clusterAt) closeCluster();
          });
        }

        /**
         * What to say about a face on the map.
         *
         * <p>Both clocks for somebody who has gone: how long ago, because that
         * is the question, and the time itself, because "three hours ago" is
         * the answer you have to do arithmetic on before you can compare it to
         * anything else you know.
         */
        function headStory(el){
          const who=el.getAttribute('data-who')||'';
          const state=el.getAttribute('data-state');
          const at=+el.getAttribute('data-at')||0;
          const still=+el.getAttribute('data-still')||0;
          if(state==='gone'){
            return who+' left here '+fmtAgo(at)+
              (fmtWhen(at)?', at '+fmtWhen(at):'')+
              ' · click to show only them';
          }
          if(state==='afk'){
            return who+' — not moving for '+humanSeconds(still)+
              ', since '+fmtWhen(at)+' · click to show only them';
          }
          return who+' — here, last moved '+fmtAgo(at)+' · click to show only them';
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
              placeTip(tip,el,svg,box);
            });
            el.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          });
        }

        /** "the_nether" is what the game calls it; "Nether" is what people do. */
        function prettyDim(d){
          if(!d) return '';
          const known={overworld:'Overworld', the_nether:'Nether', the_end:'The End'};
          if(known[d]) return known[d];
          return d.replace(/^the_/,'').replace(/_/g,' ')
                  .replace(/^./,ch=>ch.toUpperCase());
        }

        function wireDims(){
          const host=$('t-dims'); if(!host) return;
          host.querySelectorAll('[data-tdim]').forEach(b=>
            b.onclick=()=>{
              const next=b.getAttribute('data-tdim');
              if(next!==allDim){ blueScene=null; bluePicked=''; }
              allDim=next; paintAll();
            });
        }

        """;

    /**
     * What the log meant, and the rest of the activity tab.
     *
     * <p>Split from PARTMAP at the 64KB ceiling on one string constant, on the
     * boundary between drawing the map and everything around it.
     */
    private static final String PARTINSIGHT = """
        // ---- what it all meant ----
        // Two layers, and the lower one is the one that always works. Episodes
        // are worked out on the server from the log itself — no model, no
        // network, no key — and are most of the value: "dug a shaft from y 64
        // down to y 11" is a sentence the log never contained. The summary on
        // top of them is optional and off by default.
        let episodes=[], aiStatus=null, aiReport=null, summarising=false;
        // Which of the three subjects the summary is about. 'view' follows the
        // map, so zooming in and pressing Summarise asks about what is on
        // screen rather than about the whole server again.
        let aiScope='all';
        let asked=null, asking=false;

        /**
         * What the map is currently showing, as a scope the server understands.
         *
         * <p>The radius comes from the view itself, so "summarise this" means
         * the same thing as "what I can see" without anybody drawing a box.
         */
        function scopeNow(){
          if(aiScope==='player' && focusPlayer){
            return {scope:'player', player:focusPlayer};
          }
          if(aiScope==='view'){
            const span=Math.max(32,Math.round(view.span||512));
            return {scope:'area', dim:allDim||'', x:Math.round(view.cx||0),
                    z:Math.round(view.cz||0), r:Math.round(span/2)};
          }
          return {scope:'all'};
        }

        function scopeQuery(){
          const q=scopeNow();
          return Object.keys(q).map(k=>k+'='+encodeURIComponent(q[k])).join('&');
        }

        /** Whether the map is zoomed in far enough for "this view" to mean anything. */
        function zoomedIn(){
          return !!(view.set && view.span && view.span<=1400);
        }

        async function loadInsights(){
          const box=$('i-eps'); if(!box) return;
          lastInsight=Date.now();
          // A scope nobody can be in any more falls back rather than sticking:
          // un-focusing a player should not leave the button asking about them.
          if(aiScope==='player' && !focusPlayer) aiScope='all';
          const r=await jget('/api/insights?'+scopeQuery());
          if(r.status!==200){ box.innerHTML='<div class="note">unavailable</div>'; return; }
          episodes=r.body.episodes||[];
          aiStatus=r.body.ai||null;
          aiReport=r.body.report||null;
          paintInsights();
        }

        async function runSummary(force){
          if(summarising) return;
          summarising=true;
          paintInsights();
          try {
            const r=await jpost('/api/insights',scopeNow());
            if(r.status===200){
              episodes=r.body.episodes||episodes;
              aiStatus=r.body.ai||aiStatus;
              aiReport=r.body.report||null;
            } else {
              aiReport={error:(r.body&&r.body.error)||'failed',moments:[],summary:''};
            }
          } finally {
            summarising=false;
            paintInsights();
            // Moments show up as marks on the timeline, so it needs redrawing.
            if(allData) paintTimeline();
          }
        }

        /**
         * "What are you looking for", answered as a filter.
         *
         * <p>What comes back is set into the panel's own filter controls, not
         * applied invisibly. The person can then see what was decided for
         * them, widen it, or throw it away — none of which is possible with a
         * list of results that appeared from nowhere.
         */
        async function runAsk(){
          const inp=$('i-ask'); if(!inp || asking) return;
          const q=(inp.value||'').trim();
          if(!q){ asked={error:'Say what you are looking for first.'}; paintAsked(); return; }
          asking=true; asked={pending:true,question:q}; paintAsked();
          try {
            const r=await jpost('/api/insights/find',{question:q});
            if(r.status!==200){
              asked={question:q,error:(r.body&&(r.body.error||r.body.reply))||'failed'};
              return;
            }
            asked=r.body;
            applyLens(r.body);
          } catch(e){
            asked={question:q,error:'failed — '+e.message};
          } finally {
            asking=false;
            paintAsked();
            paintFilters();
            paintAll();
            paintInsights();
          }
        }

        /** Sets the filter controls to what the model picked out. */
        function applyLens(lens){
          clearFilter();
          for(const a of (lens.actions||[])) if(ACT_CATEGORY[a]) filt.acts.add(a);
          for(const i of (lens.items||[])) filt.items.add(i);
          for(const k of (lens.kinds||[])) filt.kinds.add(k);
          // One player named and no others is a focus, which the map already
          // knows how to do and does better than a filter would.
          const who=lens.players||[];
          focusPlayer = who.length===1 ? who[0] : '';
        }

        function paintAsked(){
          const box=$('i-asked'); if(!box) return;
          if(!asked){ box.innerHTML=''; return; }
          if(asked.pending){
            box.innerHTML='<div class="note">reading the log for “'+esc(asked.question)+'”…</div>';
            return;
          }
          if(asked.error){
            box.innerHTML='<div class="msg err">'+esc(asked.error)+'</div>';
            return;
          }
          const bits=[];
          if((asked.players||[]).length) bits.push((asked.players||[]).join(', '));
          if((asked.actions||[]).length) bits.push((asked.actions||[]).join(', '));
          if((asked.kinds||[]).length) bits.push((asked.kinds||[]).join(', '));
          const n=(asked.items||[]).length;
          if(n) bits.push(n+' particular thing'+(n===1?'':'s'));
          box.innerHTML='<div class="note">'+
            (asked.reply?esc(asked.reply)+' ':'')+
            (bits.length?'<b>Filtered to:</b> '+esc(bits.join(' · '))+'. '
                        :'<b>Nothing was filtered out.</b> ')+
            'Change it in <b>Filter</b>, or press Clear.</div>';
        }

        /**
         * Patterns the rules could not have found.
         *
         * <p>Kept in its own block above the episode list and labelled as the
         * model's, because the two are different kinds of claim. An episode is
         * counted from the log and is true. One of these is something a model
         * thought it saw across an evening, and the only honest way to show it
         * is separately, with the times it is talking about.
         */
        function paintFound(){
          const box=$('i-found'); if(!box) return;
          box.innerHTML='';
          const found=(aiReport&&aiReport.patterns)||[];
          if(!found.length) return;
          const sec=document.createElement('div');
          sec.innerHTML='<h3 style="font-size:11px;text-transform:uppercase;'+
            'letter-spacing:.9px;color:var(--brand);margin:14px 0 4px">'+
            'Patterns the model noticed</h3>'+
            '<p class="muted" style="margin:0 0 6px;font-size:11.5px">Not counted from the '+
            'log like the list below — spotted across it, and worth checking rather than '+
            'believing.</p>';
          box.appendChild(sec);
          for(const f of found){
            const row=document.createElement('div');
            row.className='moment';
            row.innerHTML='<span class="lb">'+esc(f.label)+'</span>'+
              (f.player?'<span class="muted">'+esc(f.player)+'</span>':'')+
              (f.why?'<span class="wy">'+esc(f.why)+'</span>':'')+
              '<span class="tm" style="margin-left:auto;color:var(--mute)">'+
              esc(fmtAgo(f.to))+'</span>';
            row.title='From '+(fmtWhen(f.from)||fmtAgo(f.from))+
              ' to '+(fmtWhen(f.to)||fmtAgo(f.to));
            row.onclick=()=>jumpTo(f.to,'',undefined,undefined);
            box.appendChild(row);
          }
        }

        function paintInsights(){
          paintAiBox();
          paintFound();
          const box=$('i-eps'); if(!box) return;
          box.innerHTML='';
          if(!episodes.length){
            box.innerHTML='<div class="note">Nothing has happened yet, or the activity '+
              'log is off.</div>';
            return;
          }
          const head=document.createElement('p');
          head.className='muted';
          head.style.margin='4px 0 8px';
          head.textContent=episodes.length+' thing'+(episodes.length===1?'':'s')+
            ' worked out from the log — click one to take the map there.';
          box.appendChild(head);
          for(const e of episodes.slice(0,40)){
            const row=document.createElement('div');
            row.className='episode';
            row.appendChild(avatar(e.player,e.uuid,'sm'));
            const body=document.createElement('div');
            const means=meaningFor(e);
            body.innerHTML='<span class="kind">'+esc(e.kind)+'</span>'+
              '<span class="nm" style="font-weight:650">'+esc(e.mask||e.player)+'</span> '+
              esc(e.headline)+
              // The model's reading of it, under the fact rather than instead
              // of it: the sentence above is what happened and is certain, and
              // this is what it was probably for and is not.
              (means?'<div class="means">'+esc(means)+'</div>':'');
            row.appendChild(body);
            const when=document.createElement('span');
            when.className='tm';
            when.textContent=fmtAgo(e.to);
            row.appendChild(when);
            row.onclick=()=>jumpTo(e.to,e.dim,e.x,e.z,e.y);
            if(hasShape(e)){
              const look=document.createElement('button');
              look.className='btn'; look.textContent='3D';
              look.style.padding='2px 8px'; look.style.fontSize='11.5px';
              look.title='Draw the blocks this changed';
              look.onclick=ev=>{ ev.stopPropagation(); openScene(e); };
              row.appendChild(look);
            }
            box.appendChild(row);
          }
        }

        /** Takes the map and the timeline to one moment and place. */
        function jumpTo(at,dim,x,z,y){
          if(!allData) return;
          live=false; stopPlay();
          cursorAt=at; cursorSet=true;
          if(dim) allDim=dim;
          if(x!==undefined && z!==undefined){
            view.cx=x; view.cz=z; view.span=Math.min(view.span||160,160); view.set=true;
            if(usingBlueMap()) focusBlueMap(x,y||0,z,140);
          }
          paintAll();
          const map=$('t-map');
          if(map) map.scrollIntoView({block:'center',behavior:'smooth'});
        }

        /**
         * The summary box: what the model said, or why there is nothing there.
         *
         * <p>The "why" cases matter more than the summary does. Off by default
         * is the right default, and an admin who has just pressed Summarise
         * needs to be told which of "not switched on", "no key", "no address"
         * and "the service said no" they are looking at.
         */
        /**
         * The three subjects the summary can be about.
         *
         * <p>"This view" only appears once the map is actually zoomed into
         * something, and "This player" only once one is focused: a chip that
         * would mean the same as "everything" is a chip that teaches people
         * the control does nothing.
         */
        function paintScopeChips(){
          const box=$('i-scope'); if(!box) return;
          const choices=[{k:'all', label:'Everything'}];
          if(focusPlayer) choices.push({k:'player', label:focusPlayer});
          if(zoomedIn()) choices.push({k:'view', label:'This view'});
          if(!choices.some(c=>c.k===aiScope)) aiScope='all';
          box.innerHTML='';
          for(const c of choices){
            const b=document.createElement('button');
            b.textContent=c.label;
            if(aiScope===c.k) b.className='on';
            b.title = c.k==='view'
              ? 'Summarise only what is on the map right now'
              : (c.k==='player' ? 'Summarise only '+c.label : 'Summarise the whole server');
            b.onclick=()=>{ aiScope=c.k; loadInsights(); };
            box.appendChild(b);
          }
        }

        function paintAiBox(){
          paintScopeChips();
          const box=$('i-ai'), run=$('i-run'); if(!box) return;
          const on=aiStatus && aiStatus.enabled;
          const bar=$('i-askbar');
          if(bar) bar.style.display=on?'':'none';
          if(run){
            run.disabled=summarising || !on;
            run.textContent=summarising?'Thinking…'
              :(aiScope==='player'?'Summarise '+focusPlayer
               :aiScope==='view'?'Summarise this view':'Summarise');
            run.title=on?'Ask the model to read the episodes below'
                        :'Turn on ai-enabled in Settings first';
          }
          if(!on){
            box.innerHTML='<div class="note">'+
              '<b>Summaries are off.</b> Almin can hand the list below to a language '+
              'model and get a paragraph back saying what the session was about. '+
              'Turn on <code>ai-enabled</code> in Settings — that page also says '+
              'exactly what gets sent, and to whom.</div>';
            return;
          }
          const trouble=aiStatus.problem||'';
          const where=aiStatus.provider==='local'
            ? 'a model on this machine ('+esc(aiStatus.baseUrl||'')+')'
            : esc(aiStatus.provider);
          let html='<p class="muted" style="margin:2px 0 8px">Using '+
            '<code>'+esc(aiStatus.model||'?')+'</code> via '+where+
            (aiStatus.sendChat?' · chat included':' · chat withheld')+
            (aiStatus.autoMinutes>0?' · refreshed every '+aiStatus.autoMinutes+' min':'')+
            '</p>';
          if(trouble){
            html+='<div class="msg err">'+esc(trouble)+'</div>';
            box.innerHTML=html; return;
          }
          if(aiReport && aiReport.error){
            html+='<div class="msg err">'+esc(aiReport.error)+'</div>';
            box.innerHTML=html; return;
          }
          if(!aiReport){
            html+='<div class="note">Nothing summarised yet. Press Summarise.</div>';
            box.innerHTML=html; return;
          }
          if(aiReport.summary){
            html+='<div class="summary">'+esc(aiReport.summary)+'</div>';
          }
          const moments=aiReport.moments||[];
          if(moments.length){
            html+='<h3 style="font-size:11px;text-transform:uppercase;letter-spacing:.9px;'+
              'color:var(--brand);margin:14px 0 4px">Worth a look</h3><div id="i-moments">'+
              '</div>';
          }
          html+='<p class="muted" style="margin-top:10px;font-size:11.5px">'+
            'Written by a model from the list below — it can be wrong, and it is not '+
            'evidence of anything. '+esc(fmtAgo(aiReport.generated))+
            (aiScope==='player'?' · about '+esc(focusPlayer)
             :aiScope==='view'?' · about what is on the map':'')+'.</p>';
          box.innerHTML=html;

          const list=$('i-moments');
          if(list){
            for(const m of moments){
              const row=document.createElement('div');
              row.className='moment';
              row.innerHTML='<span class="lb">'+esc(m.label)+'</span>'+
                (m.player?'<span class="muted">'+esc(m.player)+'</span>':'')+
                (m.why?'<span class="wy">'+esc(m.why)+'</span>':'')+
                '<span class="tm" style="margin-left:auto;color:var(--mute)">'+
                esc(fmtAgo(m.at))+'</span>';
              row.onclick=()=>jumpTo(m.at,m.dim,m.x,m.z,m.y);
              list.appendChild(row);
            }
          }
        }

        """;

    /** The isometric activity scene, separate so neither script constant hits 64KB. */
    private static final String PARTSCENE = """
        // ---- what a stretch of work actually built ----
        // The map answers "where"; a top-down mark cannot answer "what shape".
        // The log knows every block that went down and every one that came up,
        // with its height, so the shape is recoverable — not the world around
        // it, which was never recorded, but the part somebody changed, which
        // is the part in question.
        const SCENE_MAX=64;          // blocks across, as asked
        const SCENE_CUBES=4000;      // beyond this it is a wall of cubes anyway
        let scene=null;

        /** Whether this stretch has enough shape in it to be worth drawing. */
        /** Stretches whose shape is made of blocks. */
        const SCENE_BUILD=['build','shaft','tunnel','mine','dig','clear','tree','farm',
                           'tower','bridge','redstone','hazard'];

        /** Stretches whose shape is made of blows. */
        const SCENE_FIGHT=['fight','pvp','grind','death'];

        /**
         * Whether a stretch has enough shape in it to be worth drawing, and
         * which of the two pictures it is.
         *
         * <p>They are separate on purpose. A fight and a building that
         * happened in the same place are two events, and drawing them in one
         * scene made a picture of neither: the blows floated over a wall that
         * had nothing to do with them, and the wall was drawn at whatever
         * scale the blows demanded.
         */
        function sceneKind(e){
          if(!e) return '';
          if(SCENE_FIGHT.includes(e.kind)) return e.events>=3 ? 'fight' : '';
          if(SCENE_BUILD.includes(e.kind)) return e.events>=8 ? 'build' : '';
          return '';
        }

        function hasShape(e){ return sceneKind(e)!==''; }

        /** Stable identity shared by a raw activity row and its scene cube. */
        function sceneActionKey(a,player,dim){
          return [player||a.player||'',dim||a.dim||'',a.at,a.wx===undefined?a.x:a.wx,
                  a.y,a.wz===undefined?a.z:a.wz,
                  a.action||(a.put?'place':'break')].join(String.fromCharCode(31));
        }

        // Playback can repaint twenty times a second. Work out the exact set
        // represented by each badge once per fetched period/episode list, not
        // once per frame.
        let sceneKeyData=null, sceneKeyEpisodes=null, sceneKeyCache=new Map();
        function scenePlaceKeysFor(e){
          if(sceneKeyData!==allData || sceneKeyEpisodes!==episodes){
            sceneKeyData=allData; sceneKeyEpisodes=episodes; sceneKeyCache=new Map();
          }
          if(sceneKeyCache.has(e)) return sceneKeyCache.get(e);
          const keys=new Set(), built=sceneOf(e);
          if(built) for(const c of built.cubes){
            if(c.put) keys.add(sceneActionKey(c,e.player,e.dim));
          }
          sceneKeyCache.set(e,keys);
          return keys;
        }

        /**
         * Everything one stretch of work touched, as blocks.
         *
         * <p>Clamped to a box around its centre, and then to the part of it
         * that is actually one thing: an episode is cut by time and by
         * distance from a running centre, so a player who dug a hole and then
         * walked thirty blocks and dug another lands both in one run. Two
         * heaps thirty blocks apart drawn in one picture are two pictures.
         */
        function sceneOf(e){
          const acts=(allData&&allData.actions)||[];
          const want=sceneKind(e);
          const cubes=[], marks=[];
          for(const a of acts){
            if(a.player!==e.player || a.dim!==e.dim) continue;
            if(a.at<e.from-1000 || a.at>e.to+1000) continue;
            // Enough slack to recover an old episode centre polluted by a
            // nearby outlier, but not enough to borrow work from an unrelated
            // episode elsewhere in the world.
            if(Math.abs(a.x-e.x)>SCENE_MAX*2 || Math.abs(a.z-e.z)>SCENE_MAX*2) continue;
            // Only what this picture is about. A fight scene that also drew
            // the wall somebody built beside it was two events in one frame,
            // and the wall decided the scale.
            if(want==='build' && (a.action==='place'||a.action==='break')){
              cubes.push({x:a.x, y:a.y, z:a.z, at:a.at, wx:a.x, wz:a.z,
                          put:a.action==='place', what:a.detail||'', n:Math.max(1,a.count||1)});
            } else if(want==='fight' &&
                      (a.action==='attack'||a.action==='hurt'||a.action==='death'
                       ||a.action==='kill')){
              marks.push({x:a.x, y:a.y, z:a.z, at:a.at, wx:a.x, wz:a.z,
                          kind:a.action, what:a.detail||''});
            }
          }
          if(!cubes.length && !marks.length) return null;

          const all=cubes.length;
          const near=cubes.length?largestHeap(cubes):[];
          const keptAbs=near.length?near:cubes;
          const focus=keptAbs.length?keptAbs:marks;
          const minX=Math.min(...focus.map(c=>c.x)), maxX=Math.max(...focus.map(c=>c.x));
          const minZ=Math.min(...focus.map(c=>c.z)), maxZ=Math.max(...focus.map(c=>c.z));
          const cx=Math.round((minX+maxX)/2), cz=Math.round((minZ+maxZ)/2);
          const kept=keptAbs.map(c=>({...c,x:c.x-cx,z:c.z-cz}));
          const localMarks=marks.map(c=>({...c,x:c.x-cx,z:c.z-cz}));
          const dropped=all-kept.length;

          const extent=kept.concat(localMarks).reduce((n,c)=>
            Math.max(n,Math.abs(c.x),Math.abs(c.z)),0);
          // A little setting around a small build, while a wide one can use
          // the full 64-block scene. Fixed 64-wide framing made a doorway a
          // speck even though there was nothing else to show.
          const radius=Math.max(8,Math.min(SCENE_MAX/2,Math.ceil(extent+6)));

          // Tracks are actual player positions (including altitude), unlike a
          // block action whose coordinates name the block. Kept solely on the
          // 3D scene: the established top-down map remains unchanged.
          const players=[];
          const tracks=(allData&&allData.tracks)||{};
          const samplePad=Math.max(5000,((allData&&allData.trackSeconds)||5)*3000);
          for(const who of Object.keys(tracks)){
            for(const p of tracks[who]||[]){
              if(p.dim!==e.dim || p.at<e.from-samplePad || p.at>e.to+samplePad) continue;
              players.push({player:who,x:p.x-cx,y:p.y,z:p.z-cz,wx:p.x,wz:p.z,at:p.at});
              if(players.length>=2000) break;
            }
            if(players.length>=2000) break;
          }
          players.sort((a,b)=>a.at-b.at);

          const shapeYs=kept.concat(localMarks).map(c=>c.y);
          const contextMinY=Math.min(...shapeYs), contextMaxY=Math.max(...shapeYs);
          // A player flying far above a four-block edit remains a real sample,
          // but is not part of the edit's geometry and must not shrink it to a
          // speck. scenePeopleAt applies the same vertical-neighbour rule.
          const minY=contextMinY, maxY=contextMaxY;
          kept.sort((a,b)=>a.at-b.at);
          localMarks.sort((a,b)=>a.at-b.at);
          const use=kept.slice(0,SCENE_CUBES);
          return {ep:e, look:want, cubes:use, marks:localMarks, players:players,
                  cx:cx,cz:cz,
                  minY:minY, maxY:maxY, radius:radius, samplePad:samplePad, turn:0,
                  contextMinY:contextMinY, contextMaxY:contextMaxY,
                  upto:Math.max(1,want==='fight'?marks.length:use.length),
                  dropped:dropped, timer:null, world:null, worldTruncated:false};
        }

        /**
         * How far apart two blocks can be and still be the same piece of work.
         *
         * <p>A dozen blocks: far enough that the two halves of one building
         * stay together, near enough that a hole somebody dug on the way to
         * another hole is a second picture rather than a corner of the first.
         */
        const SCENE_GAP=12;

        /**
         * The heap the episode is actually about.
         *
         * <p>Single-link clustering with a real distance test — the grid is
         * only there so this does not compare every block to every other one.
         * The largest group wins. Episode centres used to include unrelated
         * rows, so using that centre to choose the group recreated the very
         * outlier bug this clustering is meant to remove.
         */
        function largestHeap(cubes){
          if(cubes.length<2) return cubes;
          const cell=SCENE_GAP;
          const bins=new Map();
          cubes.forEach((c,i)=>{
            const key=Math.floor(c.x/cell)+','+Math.floor(c.y/cell)+','+Math.floor(c.z/cell);
            if(!bins.has(key)) bins.set(key,[]);
            bins.get(key).push(i);
          });
          const owner=cubes.map((_,i)=>i);
          const find=i=>{ while(owner[i]!==i){ owner[i]=owner[owner[i]]; i=owner[i]; } return i; };
          const join=(a,b)=>{ const ra=find(a), rb=find(b); if(ra!==rb) owner[rb]=ra; };
          cubes.forEach((c,i)=>{
            const gx=Math.floor(c.x/cell), gy=Math.floor(c.y/cell), gz=Math.floor(c.z/cell);
            for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++) for(let dz=-1;dz<=1;dz++){
              const near=bins.get((gx+dx)+','+(gy+dy)+','+(gz+dz));
              if(!near) continue;
              for(const j of near){
                if(j<=i) continue;
                const o=cubes[j];
                if(Math.abs(o.x-c.x)<=SCENE_GAP && Math.abs(o.y-c.y)<=SCENE_GAP
                   && Math.abs(o.z-c.z)<=SCENE_GAP) join(i,j);
              }
            }
          });
          const groups=new Map();
          cubes.forEach((c,i)=>{
            const root=find(i);
            if(!groups.has(root)) groups.set(root,[]);
            groups.get(root).push(c);
          });
          if(groups.size<2) return cubes;
          let best=null, bestScore=-1;
          for(const g of groups.values()){
            const score=g.reduce((n,c)=>n+Math.max(1,c.n||1),0);
            if(score>bestScore){ bestScore=score; best=g; }
          }
          return best||cubes;
        }

        function openScene(e){
          if(usingBlueMap()) { openBlueScene(e); return; }
          const built=sceneOf(e);
          if(!built){ return; }
          scene=built;
          // Fetched once per scene and kept on it: turning the view or
          // dragging the slider must not go back to the network.
          loadTerrain(e,built,t=>{ if(scene===built){ scene.terrain=t; paintScene(); } });
          loadSceneContext(e,built);
          modal('What was built here', body=>{
            body.innerHTML='<p class="muted" style="margin:0 0 10px">'+
              esc(e.player)+' · '+esc(e.headline)+' · '+esc(e.dim)+' '+built.cx+','+
              built.contextMinY+','+built.cz+
              '</p>'+
              '<div class="scene" id="sc-box"></div>'+
              '<div class="scenebar">'+
                '<button class="btn" id="sc-left">⟲</button>'+
                '<button class="btn" id="sc-right">⟳</button>'+
                '<button class="btn go" id="sc-play">Replay</button>'+
                '<button class="btn" id="sc-ground">World</button>'+
                '<button class="btn" id="sc-grid">Grid</button>'+
                '<input type="range" id="sc-at" min="1" max="'+
                  Math.max(1,built.look==='fight'?built.marks.length:built.cubes.length)+
                  '" value="'+
                  Math.max(1,built.look==='fight'?built.marks.length:built.cubes.length)+'">'+
                '<span class="muted num" id="sc-count"></span>'+
              '</div>'+
              '<div class="scenepick" id="sc-picked">Click a block to identify it and read '+
                'its exact world coordinates.</div>'+
              '<div class="scenekey">'+
                (built.look==='fight'
                  ? '<span><i style="background:#ff3b3b"></i>something was hit</span>'+
                    '<span class="muted">A fight on its own. Blocks anyone happened to '+
                    'place or break nearby belong to a different picture, so they are not '+
                    'in this one. The muted blocks are the loaded world around it now; '+
                    'the red marks remain the historical event.</span>'
                  : '<span><i style="border:2px solid #ffd34d;background:#6b5a2a"></i>placed'+
                    '</span>'+
                    '<span><i style="border:2px solid #ff5a5a;background:transparent"></i>'+
                    'broken</span>'+
                    '<span class="muted">Yellow and red are historical changes. Muted '+
                    'blocks are the loaded world around them now; player labels use '+
                    'recorded X/Y/Z positions from the time of the build.</span>')+
              '</div>';
            setTimeout(()=>{
              $('sc-left').onclick=()=>{ scene.turn=(scene.turn+3)%4; paintScene(); };
              $('sc-right').onclick=()=>{ scene.turn=(scene.turn+1)%4; paintScene(); };
              $('sc-at').oninput=()=>{ stopScene(); scene.upto=+$('sc-at').value; paintScene(); };
              $('sc-play').onclick=toggleScene;
              const g=$('sc-ground');
              g.className='btn'+(mapOpts.sceneGround?' on':'');
              g.onclick=()=>{ mapOpts.sceneGround=!mapOpts.sceneGround; saveMapOpts();
                g.className='btn'+(mapOpts.sceneGround?' on':''); paintScene(); };
              const grid=$('sc-grid');
              grid.className='btn'+(mapOpts.sceneGrid?' on':'');
              grid.onclick=()=>{ mapOpts.sceneGrid=!mapOpts.sceneGrid; saveMapOpts();
                grid.className='btn'+(mapOpts.sceneGrid?' on':''); paintScene(); };
              paintScene();
            },0);
          },{onClose:()=>{ stopScene(); scene=null; }});
        }

        /** Live, already-loaded blocks around a historical activity scene. */
        async function loadSceneContext(e,built){
          const q='/api/scene/context?dim='+encodeURIComponent(e.dim)+
            '&x='+built.cx+'&z='+built.cz+'&radius='+built.radius+
            '&minY='+(built.contextMinY-8)+'&maxY='+(built.contextMaxY+8);
          try {
            const r=await jget(q);
            if(scene!==built) return;
            if(r.status!==200 || !r.body || !Array.isArray(r.body.blocks)){
              built.world=[]; paintScene(); return;
            }
            built.world=r.body.blocks.map(b=>({
              x:(+b.x)-built.cx,y:+b.y,z:(+b.z)-built.cz,wx:+b.x,wz:+b.z,
              what:b.what||'a block',state:'world'
            })).filter(b=>Number.isFinite(b.x)&&Number.isFinite(b.y)&&Number.isFinite(b.z));
            built.worldTruncated=!!r.body.truncated;
            paintScene();
          } catch(err){
            if(scene===built){ built.world=[]; paintScene(); }
          }
        }

        function stopScene(){
          if(scene && scene.timer){ clearInterval(scene.timer); scene.timer=null; }
          const b=$('sc-play'); if(b){ b.textContent='Replay'; b.className='btn go'; }
        }

        /** Puts it up one block at a time, in the order it actually happened. */
        function toggleScene(){
          if(!scene) return;
          if(scene.timer){ stopScene(); return; }
          const b=$('sc-play'); if(b){ b.textContent='Pause'; b.className='btn on'; }
          const total=sceneSteps();
          if(scene.upto>=total) scene.upto=1;
          const step=Math.max(1,Math.round(total/120));
          scene.timer=setInterval(()=>{
            if(!scene){ return; }
            scene.upto=Math.min(total,scene.upto+step);
            const at=$('sc-at'); if(at) at.value=scene.upto;
            paintScene();
            if(scene.upto>=total) stopScene();
          },60);
        }

        /**
         * The scene, in two-to-one isometric.
         *
         * <p>Painter's algorithm: cubes are drawn back to front, which for
         * this projection means by x + z + y. Every cube is three faces — the
         * top full brightness, the two sides darker — which is all it takes
         * for a stack of them to read as solid.
         *
         * <p>The block size and the framing come from what is actually in the
         * scene rather than from a guess, so a scene never runs off the edge
         * of its own window.
         */
        /** How many steps this scene has, which depends on what it is made of. */
        function sceneSteps(){
          if(!scene) return 1;
          return Math.max(1, scene.look==='fight' ? scene.marks.length : scene.cubes.length);
        }

        /** The last recorded position of each player at this replay frame. */
        function scenePeopleAt(at){
          if(!scene) return [];
          const grouped=new Map();
          for(const p of scene.players||[]){
            if(!grouped.has(p.player)) grouped.set(p.player,[]);
            grouped.get(p.player).push(p);
          }
          const out=[];
          for(const points of grouped.values()){
            let found=null;
            for(const p of points){
              if(p.at<=at) found=p; else break;
            }
            // A sampling tick can fall just after the first build event. Use
            // that nearest sample until an earlier one exists.
            if(!found) found=points[0];
            if(found && Math.abs(found.x)<=scene.radius && Math.abs(found.z)<=scene.radius
               && found.y>=scene.contextMinY-24 && found.y<=scene.contextMaxY+24)
              out.push(found);
          }
          return out;
        }

        function paintScene(){
          const box=$('sc-box'); if(!box || !scene) return;
          const W=760, H=420;
          // A fight has no blocks to step through, so the blows are the
          // steps. Replaying one used to jump straight to the end, because
          // playback counted cubes and there were none.
          const fight=scene.look==='fight';
          const shown=fight?[]:scene.cubes.slice(0,scene.upto);
          const marks=fight
            ? scene.marks.slice(0,scene.upto)
            : scene.marks.filter(m=>m.at<=(shown.length?shown[shown.length-1].at:scene.ep.to));
          const frameAt=fight
            ? (marks.length?marks[marks.length-1].at:scene.ep.from)
            : (shown.length?shown[shown.length-1].at:scene.ep.from);
          const people=scenePeopleAt(frameAt);

          // Never let the live context reveal a changed block before replay
          // reaches it. All changed coordinates are cut from the context, and
          // the historical cube is solely responsible for that position.
          const changed=new Set(scene.cubes.map(c=>c.x+','+c.y+','+c.z));
          const world=mapOpts.sceneGround && Array.isArray(scene.world)
            ? scene.world.filter(c=>!changed.has(c.x+','+c.y+','+c.z)) : [];
          const all=shown.concat(marks,world,people);
          if(!all.length){ box.innerHTML=''; return; }

          const base=Math.min(scene.minY,...all.map(c=>c.y));
          const top=Math.max(scene.maxY,...all.map(c=>c.y+(c.player?2:1)));

          // Where everything lands at one unit per block, so the scale can be
          // chosen to fit rather than hoped at. The grid's four real-world
          // corners are part of the frame even before live context arrives.
          const framed=all.slice();
          if(mapOpts.sceneGrid){
            const r=scene.radius, y=base-1;
            framed.push({x:-r,z:-r,y:y},{x:r,z:-r,y:y},{x:r,z:r,y:y},{x:-r,z:r,y:y},
                        {x:-r,z:-r,y:top});
          }
          const lo=[1e9,1e9], hi=[-1e9,-1e9];
          for(const c of framed){
            const r=turned(c,scene.turn);
            const ix=(r.x-r.z)/2, iy=(r.x+r.z)/4-(c.y-base)/2;
            const up=c.player?1:0;
            lo[0]=Math.min(lo[0],ix-0.5); hi[0]=Math.max(hi[0],ix+0.5);
            lo[1]=Math.min(lo[1],iy-up);  hi[1]=Math.max(hi[1],iy+1);
          }
          const pad=2.2;
          const S=Math.max(3,Math.min(26,
            Math.min(W/((hi[0]-lo[0])+pad), H/((hi[1]-lo[1])+pad))));
          // Centre what there is inside the window.
          const tx=-((lo[0]+hi[0])/2)*S, ty=-((lo[1]+hi[1])/2)*S;

          const items=[];
          for(const c of world){
            const r=turned(c,scene.turn);
            items.push({key:r.x+r.z+c.y*0.5,layer:0,
                        svg:worldCube(r.x,c.y-base,r.z,S,c)});
          }
          for(const c of shown){
            const r=turned(c,scene.turn);
            items.push({key:r.x+r.z+c.y*0.5,layer:1,
                        svg:cube(r.x,c.y-base,r.z,S,c)});
          }
          for(const m of marks){
            const r=turned(m,scene.turn);
            items.push({key:r.x+r.z+m.y*0.5+0.4,layer:2,
                        svg:hitMark(r.x,m.y-base,r.z,S,m)});
          }
          for(const p of people){
            const r=turned(p,scene.turn);
            items.push({key:r.x+r.z+p.y*0.5+1,layer:3,
                        svg:scenePlayer(r.x,p.y-base,r.z,S,p)});
          }
          items.sort((a,b)=>a.key-b.key||a.layer-b.layer);

          box.innerHTML='<svg viewBox="'+(-W/2)+' '+(-H/2)+' '+W+' '+H+
            '" width="100%" height="'+H+'" role="img" '+
            'aria-label="The changed blocks, nearby world, and recorded players">'+
            sceneTextureDefs(shown.concat(world))+
            '<g transform="translate('+tx.toFixed(1)+' '+ty.toFixed(1)+')">'+
            groundPlane(S,base)+sceneGridSvg(S,base,top)+
            items.map(i=>i.svg).join('')+'</g></svg>';
          wireSceneInspect(box);

          const count=$('sc-count');
          if(count){
            if(fight){
              count.textContent=marks.length+' event'+(marks.length===1?'':'s')+
                (people.length?' \u00b7 '+people.length+' nearby player'+
                  (people.length===1?'':'s'):'');
            } else {
              let put=0, took=0;
              for(const c of shown){ if(c.put) put+=c.n; else took+=c.n; }
              count.textContent=put+' placed \u00b7 '+took+' broken'+
                (people.length?' \u00b7 '+people.length+' nearby player'+
                  (people.length===1?'':'s'):'')+
                (scene.dropped>0?' \u00b7 '+scene.dropped+' elsewhere left out':'')+
                (scene.cubes.length>=SCENE_CUBES?' \u00b7 first '+SCENE_CUBES+' only':'')+
                (scene.worldTruncated?' \u00b7 world context trimmed':'');
            }
          }
        }

        /** A world-aligned X/Z floor and Y ruler for the isometric view. */
        function sceneGridSvg(S,base,top){
          if(!scene || !mapOpts.sceneGrid) return '';
          const radius=scene.radius;
          const step=radius<=16?4:8;
          const floor=base-0.35;
          const point=(c,y)=>{
            const r=turned(c,scene.turn);
            return [isoX(r.x,r.z,S),isoY(r.x,y-base,r.z,S)];
          };
          const line=(a,b,kind)=>'<line x1="'+a[0].toFixed(1)+'" y1="'+a[1].toFixed(1)+
            '" x2="'+b[0].toFixed(1)+'" y2="'+b[1].toFixed(1)+
            '" stroke="'+(kind==='y'?'#f4b860':'#7f91aa')+'" stroke-opacity="'+
            (kind==='major'?'.54':'.32')+'" stroke-width="1" vector-effect="non-scaling-stroke"/>';
          const label=(p,text,anchor)=>'<text x="'+p[0].toFixed(1)+'" y="'+
            (p[1]-3).toFixed(1)+'" fill="#aeb9c8" font-size="9" text-anchor="'+anchor+
            '" paint-order="stroke" stroke="#0b0d11" stroke-width="3">'+esc(text)+'</text>';
          const out=[];
          const minX=scene.cx-radius, maxX=scene.cx+radius;
          const minZ=scene.cz-radius, maxZ=scene.cz+radius;
          for(let wx=Math.ceil(minX/step)*step;wx<=maxX;wx+=step){
            const dx=wx-scene.cx;
            const a=point({x:dx,z:-radius},floor), b=point({x:dx,z:radius},floor);
            out.push(line(a,b,wx%16===0?'major':'minor'),label(b,'x '+wx,'middle'));
          }
          for(let wz=Math.ceil(minZ/step)*step;wz<=maxZ;wz+=step){
            const dz=wz-scene.cz;
            const a=point({x:-radius,z:dz},floor), b=point({x:radius,z:dz},floor);
            out.push(line(a,b,wz%16===0?'major':'minor'),label(b,'z '+wz,'middle'));
          }
          const corner={x:-radius,z:-radius};
          const low=point(corner,base), high=point(corner,top);
          out.push(line(low,high,'y'));
          const yStep=(top-base)>48?16:8;
          for(let y=Math.ceil(base/yStep)*yStep;y<=top;y+=yStep){
            const p=point(corner,y);
            out.push('<line x1="'+(p[0]-4).toFixed(1)+'" y1="'+p[1].toFixed(1)+
              '" x2="'+(p[0]+4).toFixed(1)+'" y2="'+p[1].toFixed(1)+
              '" stroke="#f4b860" stroke-width="1"/>',label([p[0]-7,p[1]],'Y '+y,'end'));
          }
          return '<g class="scenegrid" pointer-events="none">'+out.join('')+'</g>';
        }

        /**
         * The ground around it, standing up.
         *
         * <p>Snapshots carry a height for every column beside the colour, so
         * the land can be built out of the same blocks the build is: one top
         * face per column at its own height, and a side face wherever the
         * ground next to it is lower. That is what makes a hillside a
         * hillside instead of a picture of one.
         *
         * <p>Only the sides that face the viewer are drawn, and only as far
         * down as the neighbour they are hiding — a full column down to
         * bedrock would be thousands of faces nobody can see.
         */
        function groundPlane(S,base){
          if(!scene || !mapOpts.sceneGround) return '';
          // The block slice is both more current and genuinely three
          // dimensional. A snapshot is the graceful fallback for an offline
          // server, an unloaded dimension, or an older panel endpoint.
          if(Array.isArray(scene.world) && scene.world.length) return '';
          const t=scene.terrain;
          if(!t || !t.ready) return '';
          const half=scene.radius;

          // A step, when the region is large enough that a face per block is
          // more faces than the picture can use.
          const span=half*2;
          const step=Math.max(1,Math.round(span/72));
          const cells=[];
          for(let dz=-half;dz<half;dz+=step){
            for(let dx=-half;dx<half;dx+=step){
              const c=t.at(dx,dz);
              if(!c) continue;
              const r=turned({x:dx,z:dz},scene.turn);
              cells.push({x:r.x, z:r.z, y:c.y, rgb:c.rgb,
                          // The neighbours that will be behind this one once
                          // it is turned, so the skirt is drawn on the sides
                          // that show.
                          right:t.at(dx+step,dz), down:t.at(dx,dz+step)});
            }
          }
          // Back to front, same rule the blocks use.
          cells.sort((a,b)=>(a.x+a.z)-(b.x+b.z));

          const out=[];
          for(const c of cells){
            const y=c.y-base;
            const ox=isoX(c.x,c.z,S)*1, oy=isoY(c.x,y,c.z,S);
            const w=S/2*step, q=S/4*step;
            const pts=a=>a.map(p=>p[0].toFixed(1)+','+p[1].toFixed(1)).join(' ');
            const top=[[ox,oy],[ox+w,oy+q],[ox,oy+2*q],[ox-w,oy+q]];
            out.push('<polygon points="'+pts(top)+'" fill="'+shadeHex(c.rgb,1)+'"/>');
            // Skirts, only as deep as the drop to the neighbour.
            const drop=(n)=>n?Math.max(0,c.y-n.y):2;
            const dr=Math.min(24,drop(c.right)), dd=Math.min(24,drop(c.down));
            if(dd>0){
              const h=dd*S/2;
              out.push('<polygon points="'+pts([[ox-w,oy+q],[ox,oy+2*q],
                [ox,oy+2*q+h],[ox-w,oy+q+h]])+'" fill="'+shadeHex(c.rgb,0.74)+'"/>');
            }
            if(dr>0){
              const h=dr*S/2;
              out.push('<polygon points="'+pts([[ox,oy+2*q],[ox+w,oy+q],
                [ox+w,oy+q+h],[ox,oy+2*q+h]])+'" fill="'+shadeHex(c.rgb,0.56)+'"/>');
            }
          }
          if(!out.length) return '';
          // Pushed back, so it is the setting rather than the subject.
          return '<g opacity=".78">'+out.join('')+'</g>';
        }

        /**
         * Reads a snapshot's colours and heights into something the scene can
         * ask questions of.
         *
         * <p>Through a canvas, because a PNG is only pixels once something has
         * drawn it. Both images are same-origin, so this is allowed; a server
         * with no shape file simply comes back not ready and the scene is the
         * blocks alone.
         */
        function loadTerrain(e,built,then){
          const patch=shotsFor(e.dim,e.to).find(p=>
            p.minX<=built.cx && p.minX+p.span>built.cx &&
            p.minZ<=built.cz && p.minZ+p.span>built.cz);
          if(!patch){ then(null); return; }
          const url=a=>'/api/map?at='+patch.at+'&dim='+encodeURIComponent(e.dim)+a;
          const colour=new Image(), shape=new Image();
          let done=0, failed=false;
          const finish=()=>{
            if(++done<2) return;
            if(failed){ then(null); return; }
            try {
              const n=colour.naturalWidth;
              const c=document.createElement('canvas');
              c.width=n; c.height=colour.naturalHeight;
              const cx=c.getContext('2d');
              cx.drawImage(colour,0,0);
              const cd=cx.getImageData(0,0,c.width,c.height).data;
              const h=document.createElement('canvas');
              h.width=shape.naturalWidth; h.height=shape.naturalHeight;
              const hx=h.getContext('2d');
              hx.drawImage(shape,0,0);
              const hd=hx.getImageData(0,0,h.width,h.height).data;
              // One image pixel is span/n blocks; at the default that is one.
              const per=patch.span/n;
              then({ready:true, at:(dx,dz)=>{
                const px=Math.floor((built.cx+dx-patch.minX)/per);
                const pz=Math.floor((built.cz+dz-patch.minZ)/per);
                if(px<0||pz<0||px>=n||pz>=c.height) return null;
                const i=(pz*n+px)*4;
                if(cd[i+3]<128 || hd[i+3]<128) return null;
                return {y:((hd[i]<<8)|hd[i+1])-2048,
                        rgb:'#'+[cd[i],cd[i+1],cd[i+2]]
                          .map(v=>v.toString(16).padStart(2,'0')).join('')};
              }});
            } catch(err){ then(null); }
          };
          colour.onload=finish; shape.onload=finish;
          colour.onerror=()=>{ failed=true; finish(); };
          // No shape file is not a failure: it is an older snapshot, and the
          // scene is still worth drawing without the land around it.
          shape.onerror=()=>{ failed=true; finish(); };
          colour.src=url('');
          shape.src=url('&height=1');
        }

        /** Quarter turns about the centre, so you can see round the back. */
        function turned(c,turn){
          switch(turn&3){
            case 1:  return {x:-c.z, z:c.x};
            case 2:  return {x:-c.x, z:-c.z};
            case 3:  return {x:c.z,  z:-c.x};
            default: return {x:c.x,  z:c.z};
          }
        }

        function isoX(x,z,S){ return (x-z)*(S/2); }
        function isoY(x,y,z,S){ return (x+z)*(S/4)-y*(S/2); }

        /**
         * One block.
         *
         * <p>Filled with the colour the block actually is — the server knows,
         * from its own registry and from the texture where there is one — and
         * outlined in yellow or red for what happened to it. Two questions,
         * two channels: a wall of solid yellow said only "somebody put blocks
         * here", which the sentence above the picture had already said.
         */
        function cube(x,y,z,S,c){
          const ox=isoX(x,z,S), oy=isoY(x,y,z,S);
          const half=S/2, quarter=S/4;
          const top=[[ox,oy],[ox+half,oy+quarter],[ox,oy+half],[ox-half,oy+quarter]];
          const left=[[ox-half,oy+quarter],[ox,oy+half],[ox,oy+half+half],[ox-half,oy+quarter+half]];
          const right=[[ox,oy+half],[ox+half,oy+quarter],[ox+half,oy+quarter+half],[ox,oy+half+half]];
          const pts=a=>a.map(p=>p[0].toFixed(1)+','+p[1].toFixed(1)).join(' ');
          const title='<title>'+esc((c.put?'placed ':'broke ')+(c.what||'a block')+
            (c.n>1?' \u00d7'+c.n:'')+' at '+c.wx+','+c.y+','+c.wz)+'</title>';
          const base=blockRgb(c.what);
          const edge=c.put?'#ffd34d':'#ff5a5a';
          const line=Math.max(0.35,S*0.045);
          const attrs=sceneAttrs(c,c.put?'placed':'broken','sc-block');
          if(c.put){
            return '<g '+attrs+'>'+
              '<polygon points="'+pts(top)+'" fill="'+shadeHex(base,1.12)+'"/>'+
              sceneTextureFace(c,pts(top),'top','.94','')+
              '<polygon points="'+pts(top)+'" fill="none" stroke="'+edge+
              '" stroke-opacity=".85" stroke-width="'+line.toFixed(2)+'"/>'+
              '<polygon points="'+pts(left)+'" fill="'+shadeHex(base,0.78)+'"/>'+
              sceneTextureFace(c,pts(left),'side','.82','.20')+
              '<polygon points="'+pts(left)+'" fill="none" stroke="'+edge+
              '" stroke-opacity=".5" stroke-width="'+(line*0.7).toFixed(2)+'"/>'+
              '<polygon points="'+pts(right)+'" fill="'+shadeHex(base,0.58)+'"/>'+
              sceneTextureFace(c,pts(right),'side','.75','.34')+
              '<polygon points="'+pts(right)+'" fill="none" stroke="'+edge+
              '" stroke-opacity=".5" stroke-width="'+(line*0.7).toFixed(2)+'"/>'+
              title+'</g>';
          }
          // Broken: the block it was, ghosted, inside a red outline. A solid
          // cube would say a block is there, and the point is that one is not.
          return '<g '+attrs+'>'+
            '<polygon points="'+pts(top)+'" fill="'+shadeHex(base,1.05)+'" fill-opacity=".26" '+
            'stroke="'+edge+'" stroke-width="'+(line*1.1).toFixed(2)+'"/>'+
            sceneTextureFace(c,pts(top),'top','.24','')+
            '<polygon points="'+pts(left)+'" fill="'+shadeHex(base,0.7)+'" fill-opacity=".16" '+
            'stroke="'+edge+'" stroke-opacity=".6" stroke-width="'+(line*0.8).toFixed(2)+'"/>'+
            sceneTextureFace(c,pts(left),'side','.13','.22')+
            '<polygon points="'+pts(right)+'" fill="'+shadeHex(base,0.55)+'" fill-opacity=".12" '+
            'stroke="'+edge+'" stroke-opacity=".6" stroke-width="'+(line*0.8).toFixed(2)+'"/>'+
            sceneTextureFace(c,pts(right),'side','.10','.36')+
              title+'</g>';
        }

        /** One muted cube from the live, already-loaded surrounding world. */
        function worldCube(x,y,z,S,c){
          const ox=isoX(x,z,S), oy=isoY(x,y,z,S);
          const half=S/2, quarter=S/4;
          const top=[[ox,oy],[ox+half,oy+quarter],[ox,oy+half],[ox-half,oy+quarter]];
          const left=[[ox-half,oy+quarter],[ox,oy+half],[ox,oy+S],[ox-half,oy+quarter+half]];
          const right=[[ox,oy+half],[ox+half,oy+quarter],[ox+half,oy+quarter+half],[ox,oy+S]];
          const pts=a=>a.map(p=>p[0].toFixed(1)+','+p[1].toFixed(1)).join(' ');
          const base=blockRgb(c.what);
          return '<g '+sceneAttrs(c,'world now','sc-block')+' opacity=".48">'+
            '<polygon points="'+pts(top)+'" fill="'+shadeHex(base,1.04)+'"/>'+
            sceneTextureFace(c,pts(top),'top','.96','')+
            '<polygon points="'+pts(left)+'" fill="'+shadeHex(base,0.72)+'"/>'+
            sceneTextureFace(c,pts(left),'side','.84','.22')+
            '<polygon points="'+pts(right)+'" fill="'+shadeHex(base,0.54)+'"/>'+
            sceneTextureFace(c,pts(right),'side','.76','.36')+
            '<title>'+esc((c.what||'a block')+' in the world now at '+
              c.wx+','+c.y+','+c.wz)+'</title></g>';
        }

        /** Resource-pack textures, shared by every cube of the same block. */
        function sceneTextureDefs(blocks){
          if(!shotTextures || shotTextures==='none') return '';
          const names=[...new Set(blocks.map(c=>c.what).filter(Boolean))];
          const out=[];
          for(const name of names){
            for(const face of ['top','side']){
              out.push('<pattern id="'+texturePatternId(name,face)+'" '+
                'patternUnits="objectBoundingBox" patternContentUnits="objectBoundingBox" '+
                'width="1" height="1"><image href="/api/block?name='+
                encodeURIComponent(name)+'&face='+face+'" x="0" y="0" width="1" height="1" '+
                'preserveAspectRatio="none" style="image-rendering:pixelated"/></pattern>');
            }
          }
          return out.length?'<defs>'+out.join('')+'</defs>':'';
        }

        function texturePatternId(name,face){
          let h=2166136261;
          const s=String(name)+'/'+face;
          for(let i=0;i<s.length;i++){ h^=s.charCodeAt(i); h=Math.imul(h,16777619); }
          return 'sc-tx-'+(h>>>0).toString(36);
        }

        function sceneTextureFace(c,points,face,opacity,shade){
          if(!shotTextures || shotTextures==='none' || !c.what) return '';
          return '<polygon points="'+points+'" fill="url(#'+texturePatternId(c.what,face)+
            ')" opacity="'+opacity+'"/>'+(shade?'<polygon points="'+points+
            '" fill="#000" opacity="'+shade+'"/>':'');
        }

        /** A recorded player position, with altitude visible in the label. */
        function scenePlayer(x,y,z,S,p){
          const fx=isoX(x,z,S), fy=isoY(x,y,z,S)+S/2;
          const hy=fy-S*0.92, colour=playerColor(p.player);
          const label=p.player+' \u00b7 '+p.wx+','+p.y+','+p.wz;
          return '<g '+sceneAttrs(p,'recorded player','sc-player')+'>'+
            '<line x1="'+fx.toFixed(1)+'" y1="'+fy.toFixed(1)+'" x2="'+fx.toFixed(1)+
              '" y2="'+hy.toFixed(1)+'" stroke="#0b0d11" stroke-width="5"/>'+
            '<line x1="'+fx.toFixed(1)+'" y1="'+fy.toFixed(1)+'" x2="'+fx.toFixed(1)+
              '" y2="'+hy.toFixed(1)+'" stroke="'+colour+'" stroke-width="3"/>'+
            '<circle cx="'+fx.toFixed(1)+'" cy="'+hy.toFixed(1)+'" r="'+
              Math.max(2.5,S*0.2).toFixed(1)+'" fill="'+colour+'" stroke="#0b0d11" '+
              'stroke-width="1.5"/>'+
            '<text x="'+(fx+5).toFixed(1)+'" y="'+(hy-4).toFixed(1)+
              '" fill="#f3f5f7" font-size="10" paint-order="stroke" stroke="#0b0d11" '+
              'stroke-width="3">'+esc(label)+'</text><title>'+esc(label+' · '+fmtAgo(p.at))+
              '</title></g>';
        }

        /** Data attributes shared by inspectable scene blocks and players. */
        function sceneAttrs(c,state,kind){
          return 'class="'+kind+'" data-sc-what="'+esc(c.what||c.player||'a block')+
            '" data-sc-state="'+esc(state)+'" data-sc-x="'+c.wx+'" data-sc-y="'+c.y+
            '" data-sc-z="'+c.wz+'" style="cursor:pointer"';
        }

        function wireSceneInspect(box){
          box.querySelectorAll('.sc-block,.sc-player').forEach(el=>{
            el.onclick=ev=>{
              ev.stopPropagation();
              const info={what:el.getAttribute('data-sc-what'),
                state:el.getAttribute('data-sc-state'),x:el.getAttribute('data-sc-x'),
                y:el.getAttribute('data-sc-y'),z:el.getAttribute('data-sc-z')};
              scene.picked=info;
              const picked=$('sc-picked');
              if(picked) picked.innerHTML='<strong>'+esc(info.what)+'</strong> \u00b7 '+
                esc(info.state)+' \u00b7 X '+esc(info.x)+' / Y '+esc(info.y)+' / Z '+esc(info.z);
            };
          });
        }

        /** The colour of a block by the name the log wrote down. */
        function blockRgb(name){
          const known=blockColour && blockColour[name];
          if(known) return known;
          // Nothing to go on — a modded block, or textures the server has not
          // got. A stable colour from the name still tells two materials
          // apart, which is the job.
          if(!name) return '#8a929c';
          return 'hsl('+nameHue(name)+' 34% 58%)';
        }

        /** Brightens or darkens a colour for one face of a cube. */
        function shadeHex(colour,k){
          const m=/^#([0-9a-f]{6})$/i.exec(colour);
          if(!m) return colour;
          const v=parseInt(m[1],16);
          const cl=x=>Math.max(0,Math.min(255,Math.round(x)));
          return '#'+[cl(((v>>16)&255)*k),cl(((v>>8)&255)*k),cl((v&255)*k)]
            .map(x=>x.toString(16).padStart(2,'0')).join('');
        }

        /** Somebody hit something here. */
        function hitMark(x,y,z,S,m){
          const ox=isoX(x,z,S), oy=isoY(x,y,z,S)+S/4;
          const r=Math.max(3,S*0.42);
          return '<g><circle cx="'+ox.toFixed(1)+'" cy="'+oy.toFixed(1)+'" r="'+r.toFixed(1)+
            '" fill="#ff3b3b" fill-opacity=".55"/>'+
            '<circle cx="'+ox.toFixed(1)+'" cy="'+oy.toFixed(1)+'" r="'+(r*1.7).toFixed(1)+
            '" fill="none" stroke="#ff3b3b" stroke-opacity=".65" stroke-width="1.4"/>'+
            '<title>'+esc(m.kind+(m.what?' — '+m.what:''))+'</title></g>';
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
        /**
         * Throwing the records away, one kind at a time.
         *
         * <p>Three switches rather than one button. Almin's records live in
         * <code>config/almin/</code> rather than in the world folder, so that
         * they survive a server that will not start — which means deleting the
         * world does not delete them, and the map goes on drawing terrain
         * nobody can visit. Almin notices a new world on its own and clears
         * them; this is here for the case it cannot see, which is a world
         * regenerated from the same seed under the same name.
         */
        function clearActivity(){
          modal('Start again',(body,close)=>{
            body.innerHTML=
              '<p class="muted">Almin keeps its records beside the mod rather than inside '+
              'the world, so that a server which will not start still has a log you can '+
              'read. The cost is that deleting the world does not delete them.</p>'+
              '<p class="muted">A new world is spotted on its own, by its seed and name, '+
              'and clears all three. Regenerating with the same seed under the same name '+
              'looks exactly like a restart from here \u2014 which is what these are for.</p>'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:10px">'+
              '<input type="checkbox" id="rs-actions" style="width:auto" checked> '+
              '<span><b>What people did</b> \u2014 the activity log, and the summary made '+
              'from it</span></label>'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:8px">'+
              '<input type="checkbox" id="rs-paths" style="width:auto" checked> '+
              '<span><b>Where they walked</b> \u2014 every recorded path</span></label>'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:8px">'+
              '<input type="checkbox" id="rs-pics" style="width:auto" checked> '+
              '<span><b>The ground</b> \u2014 the pictures the map is drawn on</span></label>'+
              '<div class="row2"><button class="btn danger" id="rs-go">Delete them</button>'+
              '<button class="btn" id="rs-no">Cancel</button></div>'+
              '<div class="msg" id="rs-msg"></div>';
            $('rs-no').onclick=close;
            $('rs-go').onclick=async()=>{
              const want={actions:$('rs-actions').checked, paths:$('rs-paths').checked,
                          pictures:$('rs-pics').checked};
              if(!want.actions && !want.paths && !want.pictures){
                const x=$('rs-msg'); x.className='msg err';
                x.textContent='Nothing is ticked.'; return;
              }
              const r=await jpost('/api/reset',want);
              if(r.status!==200){
                const x=$('rs-msg'); x.className='msg err';
                x.textContent=(r.body&&(r.body.error||r.body.message))||'failed'; return;
              }
              close();
              const msg=$('a-msg');
              if(msg){ msg.className='msg ok'; msg.textContent=r.body.message||'Cleared.'; }
              trackData=null; allData=null; shots=[]; episodes=[]; aiReport=null;
              loadActivity(); loadTrackList(); loadAll(); loadInsights();
              const box=$('a-map'); if(box) box.innerHTML='';
            };
          });
        }

        """;

    /**
     * The third chunk: settings, mods and the polling loop.
     *
     * <p>Split for the same reason as the others — see {@link #HTML}.
     */
    /** The optional BlueMap-backed world renderer, kept separate at the Java string limit. */
    private static final String PARTBLUE = """
        // ---- optional full-world 3D activity map ----
        const BLUE_SOURCE='almin-activity-v1';
        let blueMapStatus=null, blueMapMode='', blueFrameReady=false, blueCamera=null;
        let blueFrameBox=null;
        let blueFocus=null, blueFocusNonce=0, blueScene=null, bluePicked='';
        let blueRefs=new Map(), lastBlueStatus=0, blueLastSend=0, blueSendTimer=null;
        let bluePendingState=null;
        try { blueMapMode=localStorage.getItem('almin.mapMode')||''; }
        catch(e){ /* one screen's preference only */ }

        function usingBlueMap(){
          return !!(blueMapStatus&&blueMapStatus.ready&&blueMapMode!=='legacy');
        }

        async function loadBlueMapStatus(force){
          if(!force && Date.now()-lastBlueStatus<5000) return;
          lastBlueStatus=Date.now();
          const was=usingBlueMap();
          const r=await jget('/api/bluemap');
          if(r.status===200) blueMapStatus=r.body;
          else blueMapStatus={installed:false,ready:false,message:r.body.error||'unavailable'};
          // Ready means main unless this browser explicitly chose Legacy.
          if(blueMapStatus.ready && !blueMapMode) blueMapMode='world';
          paintBlueMapChoice();
          if(was!==usingBlueMap()) blueFrameBox=null;
          if(allData && was!==usingBlueMap()) paintAll();
        }

        function setBlueMapMode(mode){
          blueMapMode=mode;
          try { localStorage.setItem('almin.mapMode',mode); } catch(e){}
          blueFrameReady=false; blueCamera=null; blueFrameBox=null;
          paintBlueMapChoice(); paintAll();
        }

        function paintBlueMapChoice(){
          const host=$('t-map-choice'); if(!host) return;
          host.innerHTML='';
          const s=blueMapStatus;
          if(!s){ host.innerHTML='<span class="muted">checking 3D map…</span>'; return; }
          const add=(label,cls,fn,title)=>{
            const b=document.createElement('button'); b.className='btn'+(cls?' '+cls:'');
            b.textContent=label; b.title=title||''; b.onclick=fn; host.appendChild(b); return b;
          };
          if(s.ready){
            const tag=document.createElement('span'); tag.className='state good';
            tag.textContent='BlueMap'+(s.version?' '+s.version:''); host.appendChild(tag);
            add('3D world',usingBlueMap()?'on':'',()=>setBlueMapMode('world'),
              'BlueMap terrain with Almin activity in world coordinates');
            add('Legacy 2D',!usingBlueMap()?'on':'',()=>setBlueMapMode('legacy'),
              'The original recorded-snapshot activity map');
            return;
          }
          if(!s.installed){
            add('Install 3D world map','go',blueMapInstallDialog,
              'Install BlueMap as a separate optional server mod');
            return;
          }
          if(!s.configured){
            const tag=document.createElement('span'); tag.className='state warn';
            tag.textContent='BlueMap needs setup'; host.appendChild(tag);
            add('Connect',s.enabled?'go':'',configureBlueMap,
              'Bind BlueMap to loopback and add Almin’s web bridge');
            add('Legacy 2D','on',()=>setBlueMapMode('legacy'));
            return;
          }
          const tag=document.createElement('span'); tag.className='state warn';
          tag.textContent=s.downloadAccepted===false?'BlueMap needs approval':
            (s.loaded?'BlueMap starting':'BlueMap installed'); host.appendChild(tag);
          const note=document.createElement('span'); note.className='muted';
          note.textContent=s.message||'Restart the server to load it.'; host.appendChild(note);
          if(s.downloadAccepted===false) add('Allow resource download','go',acceptBlueMapDownload,
            'Let BlueMap download the Minecraft client resources required to render the map');
          if(s.restartRequired) add('Restart to finish','go',()=>$('srvrestart').click());
          add('Legacy 2D','on',()=>setBlueMapMode('legacy'));
        }

        function blueMapInstallDialog(){
          modal('Add the 3D world map',body=>{
            body.innerHTML='<p>Install <b>BlueMap</b> from Modrinth for this server’s exact '+
              'Minecraft version.</p><p class="muted">BlueMap remains a separate optional '+
              'Fabric mod under its own licence. Almin does not bundle or link its code or '+
              'web assets. It configures BlueMap’s own server on loopback and reaches it through '+
              'the authenticated panel.</p><p class="muted">The download is placed in '+
              '<code>mods/</code>. A restart is required before the renderer can build and serve '+
              'the world.</p><button class="btn go" id="bm-install">Install BlueMap</button>'+
              '<div class="msg" id="bm-install-msg"></div>';
            $('bm-install').onclick=installBlueMap;
          });
        }

        async function installBlueMap(){
          const button=$('bm-install'), msg=$('bm-install-msg');
          button.disabled=true; msg.className='msg'; msg.textContent='Downloading from Modrinth…';
          const got=await jpost('/api/mods/modrinth',
            {action:'server',link:'bluemap',required:false});
          if(got.status!==200){
            button.disabled=false; msg.className='msg err';
            msg.textContent=got.body.error||got.body.message||'Download failed.'; return;
          }
          msg.textContent='Installed. Securing the web connection…';
          const linked=await jpost('/api/bluemap',{action:'configure'});
          msg.className='msg '+(linked.status===200?'ok':'err');
          msg.textContent=linked.body.message||linked.body.error||got.body.message||'Installed.';
          await loadBlueMapStatus(true); loadServerMods();
        }

        async function configureBlueMap(){
          const r=await jpost('/api/bluemap',{action:'configure'});
          if(r.status!==200){ alert(r.body.error||'BlueMap setup failed.'); return; }
          await loadBlueMapStatus(true);
          if(r.body.restartRequired && confirm(r.body.message+'\\n\\nRestart now?')){
            $('srvrestart').click();
          }
        }

        async function acceptBlueMapDownload(){
          if(!confirm('BlueMap needs to download Minecraft client resources from Mojang to render the world. Allow that download for this server?')) return;
          const r=await jpost('/api/bluemap',{action:'accept-download'});
          if(r.status!==200){ alert(r.body.error||'Could not update BlueMap.'); return; }
          await loadBlueMapStatus(true);
          if(confirm((r.body.message||'BlueMap is ready to start.')+'\\n\\nRestart now?'))
            $('srvrestart').click();
        }

        function focusBlueMap(x,y,z,distance){
          blueFocus={x:+x||0,y:+y||0,z:+z||0,distance:distance||140,
                     nonce:++blueFocusNonce};
        }

        function openBlueScene(e){
          const built=sceneOf(e); if(!built) return;
          blueScene=e;
          live=false; stopPlay(); cursorAt=e.to; cursorSet=true;
          allDim=e.dim; focusBlueMap(e.x,e.y,e.z,Math.max(55,built.radius*3.5));
          bluePicked='<strong>'+esc(e.player)+'</strong> · '+esc(e.headline)+
            ' · shown in the world at X '+e.x+' / Y '+e.y+' / Z '+e.z;
          paintAll();
          const map=$('t-map'); if(map&&map.scrollIntoView)
            map.scrollIntoView({block:'center',behavior:'smooth'});
        }

        /** Draws the shared map chrome without replacing the iframe on every playback frame. */
        function paintBlueMap(d){
          const box=$('t-map'), side=$('t-side'), layout=$('t-layout');
          if(!box||!blueMapStatus) return;
          const wide=layout ? layout.clientWidth>=900 : false;
          const sidebar=wide&&mapOpts.overlays;
          if(layout) layout.className='maplayout'+(sidebar?' side':'')+(fullMap?' fullmap':'');
          if(side) side.style.display=sidebar?'':'none';

          const seqCandidates=(mapOpts.sequences?episodes:[]).filter(e=>e.dim===allDim && e.from<=d.cursor &&
            (!focusPlayer||e.player===focusPlayer) &&
            (!filt.kinds.size||filt.kinds.has(e.kind)) &&
            ageOpacity('seq',d.cursor-e.to,d.windowMs)>0);
          const buildScenes=seqCandidates.filter(e=>sceneKind(e)==='build');
          const keys=new Set();
          for(const e of buildScenes) for(const key of scenePlaceKeysFor(e)) keys.add(key);
          const scenePlaces=keys.size?d.shownActs.filter(a=>a.action==='place'&&
            keys.has(sceneActionKey(a))):[];

          let frame=null;
          if(blueFrameBox!==box){
            blueFrameReady=false;
            box.innerHTML='<div class="mapwrap bluemapwrap">'+
              '<iframe id="t-blue-frame" src="'+esc(blueMapStatus.path||'/bluemap/')+'" '+
                'title="BlueMap 3D world and Almin activity"></iframe>'+
              '<div class="onlinebar" id="t-online"></div>'+
              '<button class="sceneexpand" id="t-scene-events"></button>'+
              '<div class="bluepicked" id="t-blue-picked"></div>'+
              '<div class="mapbtns">'+
                '<button id="t-blue-home" title="Fit recorded activity">⌂</button>'+
                '<button id="t-blue-scene-close" title="Close the selected 3D event">×</button>'+
                '<button id="t-full" title="'+(fullMap?'Leave fullscreen (Esc)':'Fullscreen')+
                  '">'+(fullMap?'⤡':'⤢')+'</button>'+
                '<button id="t-cog" title="How the map looks">'+ICON.cog+'</button>'+
              '</div><div id="t-blue-opts"></div></div>';
            blueFrameBox=box;
            frame=$('t-blue-frame');
            frame.onload=()=>{ blueFrameReady=false; sendBlueMapState(bluePendingState); };
          } else frame=$('t-blue-frame');
          const online=$('t-online'); if(online) online.style.display=mapOpts.overlays?'':'none';
          paintOnline(d.online,d.ids);
          const picked=$('t-blue-picked');
          if(picked){ picked.innerHTML=bluePicked; picked.style.display=bluePicked?'':'none'; }
          const expand=$('t-scene-events');
          if(expand){
            expand.style.display=scenePlaces.length?'':'none';
            expand.textContent=(mapOpts.sceneEvents?'Collapse ':'Expand ')+scenePlaces.length+
              ' build event'+(scenePlaces.length===1?'':'s');
            expand.onclick=()=>{ mapOpts.sceneEvents=!mapOpts.sceneEvents; saveMapOpts(); paintAll(); };
          }
          const opts=$('t-blue-opts');
          if(opts) opts.innerHTML=optsOpen?mapOptionsHtml():'';
          const full=$('t-full'); if(full){ full.textContent=fullMap?'⤡':'⤢';
            full.onclick=()=>setFull(!fullMap); }
          const cog=$('t-cog'); if(cog) cog.onclick=()=>{ optsOpen=!optsOpen; paintAll(); };
          const home=$('t-blue-home'); if(home) home.onclick=()=>{
            blueCamera=null; view.set=false;
            const pts=d.shownActs.length?d.shownActs:[].concat(...d.shownNames.map(n=>d.tracks[n]||[]));
            if(pts.length){
              const xs=pts.map(p=>p.x), zs=pts.map(p=>p.z);
              const cx=(Math.min(...xs)+Math.max(...xs))/2;
              const cz=(Math.min(...zs)+Math.max(...zs))/2;
              const span=Math.max(Math.max(...xs)-Math.min(...xs),
                                  Math.max(...zs)-Math.min(...zs),64);
              focusBlueMap(cx,0,cz,Math.min(30000,Math.max(80,span*.9)));
            }
            paintAll();
          };
          const closeScene=$('t-blue-scene-close');
          if(closeScene){
            closeScene.style.display=blueScene?'':'none';
            closeScene.onclick=()=>{ blueScene=null; bluePicked=''; paintAll(); };
          }

          const payload=blueMapPayload(d,seqCandidates,buildScenes,scenePlaces);
          queueBlueMapState(payload);
          paintBlueLegend(d.shownNames,d.shownActs,payload);
          paintTimeline();
          paintSide(d.acts.filter(a=>(!focusPlayer||a.player===focusPlayer)&&passes(a)));
          wireMapOptions(); wireDims(); paintFilters(); paintBlueMapChoice();
        }

        function blueMapPayload(d,seqCandidates,buildScenes,scenePlaces){
          const centre=blueCamera||{x:view.cx||0,y:64,z:view.cz||0,
                                    distance:Math.max(90,(view.span||320)/2)};
          const distance=Math.max(20,+centre.distance||300);
          const radius=Math.max(160,Math.min(60000,distance*3.2));
          const nearby=a=>!blueCamera || (Math.abs(a.x-centre.x)<=radius&&
                                         Math.abs(a.z-centre.z)<=radius);
          const colour=a=>mapOpts.colour==='player'?playerColor(a.player):
            (ACTION_COLOR[a.action]||'#9aa3ae');
          const opacity=a=>ageOpacity(ACT_CATEGORY[a.action]||'things',
                                      d.cursor-a.at,d.windowMs);
          const hidden=new Set(mapOpts.sceneEvents?[]:scenePlaces);
          const actions=d.shownActs.filter(a=>!hidden.has(a)&&nearby(a)&&opacity(a)>0);
          const cell=mapOpts.cluster?Math.max(1,Math.round(distance/32)):1;
          const bins=new Map();
          for(const a of actions){
            const key=mapOpts.cluster?Math.floor(a.x/cell)+','+Math.floor(a.z/cell):
              a.x+','+a.y+','+a.z+','+a.at;
            if(!bins.has(key)) bins.set(key,[]); bins.get(key).push(a);
          }
          const groups=[...bins.values()].sort((a,b)=>
            Math.max(...b.map(x=>x.at))-Math.max(...a.map(x=>x.at))).slice(0,1800);
          const markers=[], lines=[], players=[], scenes=[], grid=[];
          blueRefs=new Map();
          let n=0;
          for(const group of groups){
            if(group.length===1 || !mapOpts.cluster){
              const a=group[0], id='a'+(n++);
              markers.push({id:id,kind:'action',x:a.x+.5,y:a.y+1.35,z:a.z+.5,
                color:colour(a),size:mapOpts.mark*.78,opacity:opacity(a),shape:'dot',
                text:blueActionGlyph(a.action),
                title:(a.mask?a.mask+' ('+a.player+')':a.player)+' · '+a.action+
                  (a.count>1?' ×'+a.count:'')+(a.detail?' · '+a.detail:'')+
                  ' · X '+a.x+' / Y '+a.y+' / Z '+a.z+' · '+fmtAgo(a.at)});
              blueRefs.set(id,{type:'action',data:a});
            } else {
              const total=group.reduce((v,a)=>v+Math.max(1,a.count||1),0);
              const fresh=group.reduce((a,b)=>a.at>=b.at?a:b), sx=group.reduce((v,a)=>v+a.x,0);
              const sy=group.reduce((v,a)=>v+a.y,0), sz=group.reduce((v,a)=>v+a.z,0);
              const id='c'+(n++);
              markers.push({id:id,kind:'cluster',x:sx/group.length+.5,y:sy/group.length+1.5,
                z:sz/group.length+.5,color:colour(fresh),size:mapOpts.mark*.72,
                shape:'cluster',text:total>999?'999+':String(total),
                title:total+' actions around X '+Math.round(sx/group.length)+' / Y '+
                  Math.round(sy/group.length)+' / Z '+Math.round(sz/group.length)});
              blueRefs.set(id,{type:'cluster',data:group});
            }
          }

          for(const who of d.shownNames){
            const full=(d.tracks[who]||[]).filter(p=>p.dim===allDim);
            const upto=full.filter(p=>p.at<=d.cursor);
            if(mapOpts.paths){
              const future=futureTrackPoints(thinBluePath(full),d.cursor);
              if(future.length>1) lines.push({id:'future-'+who,label:who+' path ahead',
                points:future,color:playerColor(who),width:Math.max(1,mapOpts.path*.55),
                opacity:.18});
              let runNo=0;
              for(const run of fadedTrackRuns(thinBluePath(upto),d.cursor,d.windowMs)){
                lines.push({id:'path-'+who+'-'+(runNo++),label:who+' travelled path',
                  points:run.points,color:playerColor(who),width:mapOpts.path,
                  opacity:Math.min(.98,run.opacity)});
              }
            }
            if(!upto.length) continue;
            const last=upto[upto.length-1], id='p-'+who;
            const gone=!!(d.away[who]&&d.away[who].gone&&d.away[who].at>=last.at-1000);
            const stillFor=d.cursor-last.at;
            const idle=!gone&&d.afkSecs>0&&stillFor>d.afkSecs*1000;
            const icon=(mapOpts.faces&&d.ids[who])?'/api/head?uuid='+
              encodeURIComponent(d.ids[who])+'&name='+encodeURIComponent(who):'';
            players.push({id:id,x:last.x+.5,y:last.y+2.2,z:last.z+.5,
              color:(gone||idle)?'#6d7682':playerColor(who),
              size:mapOpts.head*(gone?.62:1),text:who+(gone?' · left':idle?' · afk':''),icon:icon,
              title:who+(gone?' · left here '+fmtAgo(d.away[who].at):
                idle?' · not moving for '+humanSeconds(Math.round(stillFor/1000)):'')+
                ' · X '+last.x+' / Y '+last.y+' / Z '+last.z+' · '+fmtAgo(last.at)});
            blueRefs.set(id,{type:'player',data:{name:who,point:last}});
          }

          let episodeNo=0;
          for(const e of seqCandidates.filter(nearby).slice(0,300)){
            const id='e'+(episodeNo++);
            scenes.push({id:id,type:'marker',kind:'episode',shape:'scene',
              text:(hasShape(e)?'3D ':'')+e.kind,
              x:e.x+.5,y:e.y+2.2,z:e.z+.5,color:SEQUENCE_COLOR[e.kind]||'#ffab33',size:1,
              title:e.player+' · '+e.headline+' · X '+e.x+' / Y '+e.y+' / Z '+e.z});
            blueRefs.set(id,{type:'episode',data:e});
          }

          const cubeKeys=new Set();
          const putCube=c=>{
            const key=c.wx+','+c.y+','+c.wz+','+c.put; if(cubeKeys.has(key)) return;
            cubeKeys.add(key); if(cubeKeys.size>3000) return;
            scenes.push({id:'b'+cubeKeys.size,type:'box',kind:'block',x:c.wx,y:c.y,z:c.wz,
              color:c.put?'#ffd34d':'#ff5a5a',fill:c.put?.30:.13,
              label:c.put?'Placed block':'Broken block',detail:(c.put?'Placed ':'Broke ')+
                (c.what||'block')+' at '+c.wx+','+c.y+','+c.wz});
          };
          if(mapOpts.sceneEvents){
            for(const e of buildScenes.filter(nearby)){
              const built=sceneOf(e); if(!built) continue;
              for(const c of built.cubes) putCube(c);
              if(cubeKeys.size>3000) break;
            }
          }
          if(blueScene && blueScene.dim===allDim){
            const built=sceneOf(blueScene);
            if(built){
              for(const c of built.cubes) putCube(c);
              let q=0;
              for(const m of built.marks.slice(0,500)) scenes.push({id:'fight'+(q++),
                type:'marker',kind:'fight',shape:'dot',text:'',x:m.wx+.5,y:m.y+1,z:m.wz+.5,
                color:'#ff3d6e',size:1,title:m.kind+(m.what?' · '+m.what:'')+' · X '+m.wx+
                  ' / Y '+m.y+' / Z '+m.wz});
              q=0;
              for(const p of (built.players||[]).filter(p=>Math.abs(p.x)<=built.radius&&
                    Math.abs(p.z)<=built.radius).slice(-180)){
                const id='near'+(q++);
                scenes.push({id:id,type:'marker',kind:'scene-player',shape:'cluster',
                  text:p.player.charAt(0),x:p.wx+.5,y:p.y+1.9,z:p.wz+.5,
                  color:playerColor(p.player),size:.72,title:p.player+' nearby · X '+p.wx+
                    ' / Y '+p.y+' / Z '+p.wz+' · '+fmtAgo(p.at)});
              }
            }
          }

          if(mapOpts.grid){
            const steps=[8,16,32,64,128,256,512,1024,2048,4096,8192];
            let step=steps[steps.length-1], want=distance*.32;
            for(const s of steps){ if(s>=want){ step=s; break; } }
            const cx=Math.round(centre.x/step)*step, cz=Math.round(centre.z/step)*step;
            const y=(blueScene&&blueScene.dim===allDim?blueScene.y:
              (actions.length?actions.reduce((v,a)=>v+a.y,0)/actions.length:64))+.15;
            for(let i=-5;i<=5;i++){
              const x=cx+i*step, z=cz+i*step;
              grid.push({id:'gx'+i,points:[{x:x,y:y,z:cz-5*step},{x:x,y:y,z:cz+5*step}],
                color:x===0?'#dfe6ef':'#778394',width:x===0?1.6:.8,opacity:x===0?.55:.26});
              grid.push({id:'gz'+i,points:[{x:cx-5*step,y:y,z:z},{x:cx+5*step,y:y,z:z}],
                color:z===0?'#dfe6ef':'#778394',width:z===0?1.6:.8,opacity:z===0?.55:.26});
              grid.push({id:'gxl'+i,type:'label',x:x,y:y+.4,z:cz-5*step,
                text:'x '+x,color:'#aab5c3',size:.72,title:'X '+x});
              grid.push({id:'gzl'+i,type:'label',x:cx-5*step,y:y+.4,z:z,
                text:'z '+z,color:'#aab5c3',size:.72,title:'Z '+z});
            }
          }

          return {dimension:allDim,markers:markers,lines:lines,players:players,scenes:scenes,
            grid:grid,darkness:mapOpts.dim*.55,focus:blueFocus,
            counts:{markers:markers.length,scenes:scenes.length,actions:actions.length}};
        }

        function thinBluePath(points){
          const stride=Math.max(1,Math.ceil(points.length/500)), out=[];
          for(let i=0;i<points.length;i+=stride){ const p=points[i];
            out.push({x:p.x+.5,y:p.y+.2,z:p.z+.5,at:p.at}); }
          const last=points[points.length-1];
          const tail=out[out.length-1];
          if(last && (!tail||tail.x!==last.x+.5||tail.y!==last.y+.2||
             tail.z!==last.z+.5||tail.at!==last.at))
            out.push({x:last.x+.5,y:last.y+.2,z:last.z+.5,at:last.at});
          return out;
        }

        function blueActionGlyph(action){
          const glyph={chat:'…',command:'›',container:'□',death:'×',attack:'⚔',hurt:'!',
            join:'→',leave:'←',respawn:'↟',item:'◇',interact:'○',use:'·',place:'+',
            break:'−',afk:'z',kill:'†',craft:'◆',trade:'⇄',drop:'↓',sleep:'☾',portal:'◎',
            advancement:'★',enchant:'✦',sign:'≡'};
          return glyph[action]||'·';
        }

        function queueBlueMapState(state){
          bluePendingState=state;
          const wait=90-(Date.now()-blueLastSend);
          if(wait<=0){ sendBlueMapState(state); return; }
          if(blueSendTimer) return;
          blueSendTimer=setTimeout(()=>{ blueSendTimer=null; sendBlueMapState(bluePendingState); },wait);
        }

        function sendBlueMapState(state){
          if(!state) return;
          const frame=$('t-blue-frame');
          if(!frame||!frame.contentWindow||!blueFrameReady) return;
          blueLastSend=Date.now();
          frame.contentWindow.postMessage({source:BLUE_SOURCE,type:'state',state:state},location.origin);
        }

        function paintBlueLegend(names,acts,payload){
          const host=$('t-legend'); if(!host) return;
          const used=[...new Set(acts.map(a=>a.action))];
          host.innerHTML=names.map(n=>'<span class="pill-who" data-who="'+esc(n)+
            '" style="cursor:pointer"><i style="background:'+playerColor(n)+'"></i>'+esc(n)+
            '</span>').join('')+used.map(a=>'<span style="color:'+
              (ACTION_COLOR[a]||'#9aa3ae')+'">● '+esc(a)+'</span>').join('')+
            '<span class="muted">'+names.length+' player(s) · '+acts.length+
            ' action(s) by then · '+payload.counts.markers+' visible 3D mark(s) · terrain by BlueMap'+
            (focusPlayer?' · showing only '+esc(focusPlayer):'')+'</span>';
          host.querySelectorAll('.pill-who').forEach(el=>el.onclick=()=>{
            const n=el.getAttribute('data-who'); focusPlayer=focusPlayer===n?'':n; paintAll();
          });
        }

        async function inspectBlueWorld(p){
          bluePicked='Reading the live block at X '+p.x+' / Y '+p.y+' / Z '+p.z+'…';
          paintBluePicked();
          const q='/api/scene/context?dim='+encodeURIComponent(allDim)+'&x='+p.x+'&z='+p.z+
            '&minY='+(p.y-2)+'&maxY='+(p.y+2)+'&radius=4';
          const r=await jget(q);
          if(r.status!==200){
            bluePicked='Rendered block at X '+p.x+' / Y '+p.y+' / Z '+p.z+
              ' · live block state unavailable (the chunk may not be loaded)';
          } else {
            const exact=(r.body.blocks||[]).find(b=>b.x===p.x&&b.z===p.z&&
              (b.y===p.y||b.y===p.y-1||b.y===p.y+1));
            bluePicked=exact?'<strong>'+esc(exact.what)+'</strong> · world now · X '+exact.x+
              ' / Y '+exact.y+' / Z '+exact.z:'No exposed live block at X '+p.x+' / Y '+p.y+
              ' / Z '+p.z;
          }
          paintBluePicked();
        }

        function paintBluePicked(){
          const p=$('t-blue-picked'); if(!p) return;
          p.innerHTML=bluePicked; p.style.display=bluePicked?'':'none';
        }

        if(!window.alminBlueMessages){
          window.alminBlueMessages=true;
          window.addEventListener('message',e=>{
            const f=$('t-blue-frame');
            if(!f||e.source!==f.contentWindow||e.origin!==location.origin||
               !e.data||e.data.source!==BLUE_SOURCE) return;
            if(e.data.type==='ready'){
              blueFrameReady=true; sendBlueMapState(bluePendingState); return;
            }
            if(e.data.type==='camera'){
              blueCamera={x:+e.data.x||0,y:+e.data.y||0,z:+e.data.z||0,
                          distance:+e.data.distance||300,map:e.data.map||''};
              if(usingBlueMap()) schedulePaint();
              return;
            }
            if(e.data.type==='worldclick'){ inspectBlueWorld(e.data); return; }
            if(e.data.type!=='select') return;
            const ref=blueRefs.get(e.data.id);
            if(!ref) return;
            if(ref.type==='episode'){
              if(hasShape(ref.data)) openBlueScene(ref.data);
              else jumpTo(ref.data.to,ref.data.dim,ref.data.x,ref.data.z,ref.data.y);
              return;
            }
            if(ref.type==='player'){
              focusPlayer=focusPlayer===ref.data.name?'':ref.data.name; paintAll(); return;
            }
            if(ref.type==='cluster'){
              const g=ref.data, a=g.reduce((x,y)=>x.at>=y.at?x:y);
              bluePicked='<strong>'+g.length+' grouped actions</strong> around X '+a.x+
                ' / Y '+a.y+' / Z '+a.z; paintBluePicked(); return;
            }
            const a=ref.data;
            bluePicked='<strong>'+esc(a.detail||a.action)+'</strong> · '+esc(a.player)+' · '+
              esc(a.action)+' · X '+a.x+' / Y '+a.y+' / Z '+a.z+' · '+esc(fmtAgo(a.at));
            paintBluePicked();
          });
        }

        """;

    private static final String PART3 = """
        // ---- settings ----
        // Two different things live under Settings: Almin's own, and the
        // game's. Keeping them on one page would put a checkbox that changes
        // how Almin behaves next to one that changes how Minecraft behaves,
        // which is exactly the confusion worth avoiding.
        let settingsTab='almin';

        function settingsPanel(){
          const wrap=document.createElement('div');
          const strip=document.createElement('div');
          strip.className='subtabs';
          for(const [key,label] of [['almin','Almin'],['server','Minecraft server']]){
            const b=document.createElement('button');
            b.textContent=label;
            if(settingsTab===key) b.className='on';
            b.onclick=()=>{ settingsTab=key; render(); };
            strip.appendChild(b);
          }
          wrap.appendChild(strip);
          if(settingsTab==='server'){
            const box=document.createElement('div');
            box.innerHTML=
              '<section><h2>server.properties</h2>'+
              '<p class="muted">Minecraft\u2019s own settings, not Almin\u2019s. Almost '+
              'everything here is read when the server boots and kept in memory after '+
              'that, so <b>changes land at the next restart</b> \u2014 the file is '+
              'written straight away, comments and order untouched.</p>'+
              '<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;'+
              'margin-bottom:10px">'+
              '<input id="sp-find" placeholder="filter settings" style="flex:1;min-width:180px">'+
              '<button class="btn" id="sp-reload">Reload from disk</button>'+
              '<button class="btn go" id="sp-save" disabled>Save changes</button></div>'+
              '<div id="sp-rows"><div class="note">loading\u2026</div></div>'+
              '<div class="msg" id="sp-msg"></div></section>';
            wrap.appendChild(box);
            setTimeout(()=>{
              loadProperties();
              $('sp-reload').onclick=loadProperties;
              $('sp-save').onclick=saveProperties;
              $('sp-find').oninput=paintProperties;
            },0);
            return wrap;
          }
          const body=document.createElement('div');
          body.innerHTML=
            '<section id="s-almin"><h2>Admin password</h2>'+
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
            '<section><h2>Reading the log with a model</h2>'+
            '<p class="muted">Almin works out what happened on its own — trees felled, '+
            'shafts dug, fights, someone pacing the same twenty blocks for ten minutes — '+
            'and that costs nothing and never leaves this machine. A language model can '+
            'read that list and write a paragraph over it.</p>'+
            '<div id="s-ai" class="note">…</div>'+
            '<div class="aiform">'+
              '<label><span>Where</span><select id="s-aiprov">'+
                '<option value="local">On this machine (Ollama, llama.cpp, LM Studio)</option>'+
                '<option value="anthropic">Anthropic</option>'+
                '<option value="openai">OpenAI</option>'+
                '<option value="google">Google (Gemini)</option>'+
                '<option value="custom">Somewhere else \u2014 any OpenAI-compatible address'+
                '</option>'+
              '</select></label>'+
              '<label id="s-aiurlrow"><span>Address</span>'+
                '<input id="s-aiurl" placeholder="http://127.0.0.1:11434/v1"></label>'+
              '<div id="s-aihint" class="note" style="display:none"></div>'+
              '<label><span>Model</span>'+
                '<input id="s-aimodel" placeholder="qwen2.5:3b"></label>'+
              '<label><span>Block-layout image</span><select id="s-aiimage">'+
                '<option value="true">send when supported</option>'+
                '<option value="false">text only</option></select></label>'+
              '<label><span>API key</span>'+
                '<input id="s-aikey" type="password" autocomplete="off" '+
                'placeholder="not needed for a local model"></label>'+
              '<label><span>Summarise on its own</span>'+
                '<select id="s-aiauto">'+
                  '<option value="0">only when asked</option>'+
                  '<option value="10">every 10 minutes</option>'+
                  '<option value="30">every 30 minutes</option>'+
                  '<option value="60">every hour</option>'+
                  '<option value="180">every 3 hours</option>'+
                '</select></label>'+
            '</div>'+
            '<div class="airow">'+
              '<button class="btn go" id="s-aisave">Save</button>'+
              '<button class="btn" id="s-aion">Turn on</button>'+
              '<button class="btn" id="s-aitest">Test it</button>'+
              '<button class="btn" id="s-aidiag">Last request</button>'+
              '<button class="btn" id="s-aikeyclr">Forget the key</button>'+
            '</div>'+
            '<div class="msg" id="s-aimsg"></div>'+
            '<div id="s-aidiagbox" style="display:none;margin-top:12px"></div></section>'+
            '<section><h2>Settings</h2>'+
            '<p class="muted">Written to <code>config/almin/config.json</code> as you change them, '+
            'and live immediately.</p>'+
            '<div id="s-keys"><div class="note">loading…</div></div>'+
            '<button class="btn" id="s-reload" style="margin-top:12px">Reload from disk</button>'+
            '<div class="msg" id="s-msg"></div></section>';
          wrap.appendChild(body);
          setTimeout(()=>{
            loadConfig(); loadUpdate(); showRelaunch(); showAi();
            $('s-aisave').onclick=()=>saveAi(false);
            $('s-aion').onclick=toggleAi;
            $('s-aitest').onclick=testAi;
            $('s-aidiag').onclick=()=>showAiDiagnostics(false);
            $('s-aikeyclr').onclick=()=>saveAiKey('');
            $('s-aiprov').onchange=aiFormChanged;
            for(const id of ['s-aiurl','s-aimodel','s-aikey','s-aiimage'])
              $(id).oninput=aiFormChanged;
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
        /**
         * What turning this on would actually do, in words, before it is on.
         *
         * <p>The switch sends other people's activity to a company. Whoever
         * flips it should be told that in the place where they flip it, not in
         * a README, and told which company.
         */
        /**
         * What turning this on would actually do, in words, before it is on.
         *
         * <p>The switch sends other people's activity to a company. Whoever
         * flips it should be told that in the place where they flip it, not in
         * a README, and told which company.
         */
        let aiState=null;
        async function showAi(){
          const box=$('s-ai'); if(!box) return;
          const r=await jget('/api/insights');
          const a=(r.status===200 && r.body.ai)?r.body.ai:null;
          if(!a){ box.textContent='unavailable'; return; }
          aiState=a;
          // Fill the form from the server, once — typing into it must not be
          // overwritten by the next poll.
          const prov=$('s-aiprov');
          if(prov && !prov.almTouched){
            prov.value=a.provider||'local';
            $('s-aiurl').value=a.baseUrl||'';
            $('s-aimodel').value=a.model||'';
            $('s-aiimage').value=a.sendSceneImages===false?'false':'true';
            $('s-aiauto').value=String(a.autoMinutes||0);
          }
          const local=(prov?prov.value:a.provider)==='local';
          const leaves=local
            ? '<span class="state good">Stays on this machine</span> Almin will talk to '+
              'the address below and nothing goes to anyone else. Point it at Ollama, '+
              'llama.cpp or LM Studio; a 3B model is enough for this.'
            : '<span class="state warn">Leaves this machine</span> Player names, what they '+
              'did and where, and '+(a.sendChat?'<b>what they said in chat</b>':'not their chat')+
              (a.sendSceneImages===false?'':', plus a small diagram of block-edit positions')+
              ', are sent to <b>'+esc(prov?prov.value:a.provider)+'</b> each time a summary '+
              'is made. That is a decision about other people\u2019s data.';
          box.innerHTML=(a.enabled
              ? '<span class="state good">On</span>'
              : '<span class="state">Off</span> Fill this in and press <b>Turn on</b>.')+
            ' <span class="state '+(a.hasKey?'good':'')+'">'+
              (a.hasKey?'API key saved':'No API key saved')+'</span>'+
            '<div style="margin-top:8px">'+leaves+'</div>'+
            (a.problem&&a.enabled
              ? '<div class="msg err" style="margin-top:8px">'+esc(a.problem)+'</div>':'');
          aiFormChanged();
        }

        /**
         * Which providers keep their address in the form.
         *
         * <p>Anthropic and OpenAI have one endpoint each and it is not a
         * setting. The other three are wherever the admin says \u2014 including
         * Gemini, whose address only moves for a proxy. This is the rule the
         * address row is shown by <em>and</em> the rule it is saved by; they
         * were two separate lists, one of which said only 'local', so an
         * address you could see and type into was never written down.
         */
        function aiHasUrl(p){
          return p==='local' || p==='custom' || p==='google';
        }

        /** Marks the form as the person's rather than the server's. */
        function aiFormChanged(){
          const prov=$('s-aiprov'); if(!prov) return;
          prov.almTouched=true;
          const local=prov.value==='local', custom=prov.value==='custom';
          // Anthropic and OpenAI have one address each and it is not a
          // setting. Everything else is wherever the admin says — including
          // Gemini, whose address only moves for a proxy.
          const row=$('s-aiurlrow');
          if(row) row.style.display=aiHasUrl(prov.value)?'':'none';
          // A model name that belongs to the provider they just picked, so the
          // common case is one dropdown and a button.
          const model=$('s-aimodel');
          if(model && !model.value.trim()){
            model.placeholder=local?'qwen2.5:3b'
              :prov.value==='anthropic'?'claude-haiku-4-5'
              :prov.value==='google'?'gemini-2.0-flash'
              :custom?'the name that service uses'
              :'gpt-4o-mini';
          }
          const url=$('s-aiurl');
          if(url && local && !url.value.trim()) url.value='http://127.0.0.1:11434/v1';
          if(url) url.placeholder=local?'http://127.0.0.1:11434/v1'
            :prov.value==='google'?'https://generativelanguage.googleapis.com/v1beta'
            :'https://openrouter.ai/api/v1';
          // The whole point of "somewhere else" is that it is not one of the
          // three names above, so it has to say what it will accept.
          const hint=$('s-aihint');
          if(hint){
            hint.style.display=custom?'':'none';
            if(custom){
              hint.innerHTML='Anything that speaks the OpenAI chat API \u2014 OpenRouter, '+
                'Groq, Together, DeepSeek, Mistral, vLLM, or a model on another machine '+
                'on your network. Give the base address, up to and including '+
                '<code>/v1</code>; Almin adds <code>/chat/completions</code>. The key is '+
                'sent as a bearer token if you set one, and left out if you do not.';
            }
          }

          const on=$('s-aion');
          if(on){
            const ready=aiReady();
            on.disabled=!ready && !(aiState&&aiState.enabled);
            on.className='btn'+((aiState&&aiState.enabled)?' on':'');
            on.textContent=(aiState&&aiState.enabled)?'Turn off':'Turn on';
            on.title=on.disabled
              ? 'Fill in '+aiMissing().join(' and ')+' first'
              : ((aiState&&aiState.enabled)?'Stop summarising':'Start summarising');
          }
          const test=$('s-aitest');
          if(test) test.disabled=!aiReady();
        }

        /**
         * Whether there is enough here to talk to anything.
         *
         * <p>A hosted provider needs a key; local and custom endpoints may not.
         * The status returned by the server proves a previously typed key made
         * it all the way to the private key file.
         */
        function aiMissing(){
          const prov=$('s-aiprov'); if(!prov) return ['a provider'];
          const missing=[];
          if(!($('s-aimodel').value||'').trim()) missing.push('a model');
          // Only the two providers that have no fixed address of their own.
          // Gemini has one and it is filled in for you; the key is never part
          // of this test, because a local model does not want one.
          const needsUrl=prov.value==='local' || prov.value==='custom';
          if(needsUrl && !($('s-aiurl').value||'').trim()) missing.push('an address');
          const needsKey=prov.value==='openai' || prov.value==='anthropic' ||
            prov.value==='google';
          const typed=($('s-aikey').value||'').trim();
          if(needsKey && !typed && !(aiState&&aiState.hasKey)) missing.push('an API key');
          return missing;
        }
        function aiReady(){ return aiMissing().length===0; }

        /** Writes the form out, key included if one was typed. */
        async function saveAi(quiet){
          const msg=$('s-aimsg');
          const prov=$('s-aiprov').value;
          const sets=[['ai-provider',prov],
                      ['ai-model',($('s-aimodel').value||'').trim()],
                      ['ai-send-scene-images',$('s-aiimage').value],
                      ['ai-auto-minutes',$('s-aiauto').value]];
          if(aiHasUrl(prov)) sets.push(['ai-base-url',($('s-aiurl').value||'').trim()]);
          for(const [k,v] of sets){
            const r=await jpost('/api/config',{name:k,value:v});
            if(r.status!==200){
              if(msg){ msg.className='msg err';
                msg.textContent=(r.body&&r.body.error)||('could not set '+k); }
              return false;
            }
          }
          const key=($('s-aikey').value||'').trim();
          if(key){
            const r=await jpost('/api/ai/key',{key:key});
            if(r.status!==200){
              if(msg){ msg.className='msg err';
                msg.textContent=(r.body&&r.body.error)||'could not save the key'; }
              return false;
            }
            $('s-aikey').value='';
          }
          if(msg && !quiet){
            msg.className='msg ok';
            msg.textContent='Saved'+(key?'. The key is kept outside config.json and the '+
              'file browser will not open it.':'.');
          }
          await showAi();
          return true;
        }

        /** Saves first, then flips the switch — so it never turns on half-set. */
        async function toggleAi(){
          const msg=$('s-aimsg');
          const turningOn=!(aiState&&aiState.enabled);
          if(turningOn){
            if(!aiReady()){
              msg.className='msg err';
              msg.textContent='Fill in '+aiMissing().join(' and ')+' first.';
              return;
            }
            if(!await saveAi(true)) return;
          }
          const r=await jpost('/api/config',{name:'ai-enabled',value:turningOn?'true':'false'});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200
            ? (turningOn?'On. Summaries will be made on their own; Summarise on the '+
                         'Activity tab does one now.'
                       :'Off. Nothing more is sent.')
            : why(r);
          await showAi();
        }

        /** Saves, then asks for a summary, so "does it work" has an answer. */
        async function testAi(){
          const msg=$('s-aimsg');
          if(!await saveAi(true)) return;
          msg.className='msg';
          msg.textContent='Asking the model…';
          if(!(aiState&&aiState.enabled)){
            const on=await jpost('/api/config',{name:'ai-enabled',value:'true'});
            if(on.status!==200){ msg.className='msg err';
              msg.textContent='could not turn it on'; return; }
          }
          const r=await jpost('/api/insights',{});
          const report=r.body&&r.body.report;
          if(r.status!==200){
            msg.className='msg err';
            msg.textContent=why(r);
          } else if(report && report.error){
            msg.className='msg err';
            msg.textContent=report.error;
          } else if(report && (report.summary||'').trim()){
            msg.className='msg ok';
            msg.textContent='It works. It said: '+report.summary;
          } else {
            msg.className='msg ok';
            msg.textContent='It answered, but had nothing to say — usually an empty log.';
          }
          await showAiDiagnostics(true);
          await showAi();
        }

        /**
         * Shows exactly what left Almin and what came back. Header values are
         * never retained, so this can diagnose authentication without exposing
         * the credential itself.
         */
        async function showAiDiagnostics(force){
          const box=$('s-aidiagbox'); if(!box) return;
          if(!force && box.style.display!=='none'){
            box.style.display='none';
            $('s-aidiag').textContent='Last request';
            return;
          }
          box.style.display='';
          $('s-aidiag').textContent='Hide requests';
          box.innerHTML='<div class="note">loading request transcript…</div>';
          const r=await jget('/api/ai/diagnostics');
          if(r.status!==200){
            box.innerHTML='<div class="msg err">'+esc(why(r))+'</div>';
            return;
          }
          const rows=(r.body&&r.body.rows)||[];
          if(!rows.length){
            box.innerHTML='<div class="note">No model request has been made since Almin started.</div>';
            return;
          }
          box.innerHTML='';
          rows.forEach((d,i)=>{
            const details=document.createElement('details');
            details.open=i===0;
            const summary=document.createElement('summary');
            summary.textContent=(d.provider||'model')+' · '+
              (d.status?('HTTP '+d.status):'no HTTP response')+' · '+
              (d.elapsedMs||0)+' ms · '+new Date(d.at).toLocaleString();
            details.appendChild(summary);
            const meta=document.createElement('div');
            meta.className='note';
            meta.style.marginTop='8px';
            meta.textContent=(d.model?('Model: '+d.model+'\\n'):'')+'URL: '+d.url+
              '\\nRequest headers (names only): '+(d.requestHeaders||[]).join(', ')+
              '\\nResponse headers (names only): '+(d.responseHeaders||[]).join(', ');
            details.appendChild(meta);
            if(d.error){
              const error=document.createElement('div');
              error.className='msg err'; error.textContent=d.error;
              details.appendChild(error);
            }
            const rq=document.createElement('h3'); rq.textContent='Request body';
            const rqp=document.createElement('pre'); rqp.textContent=d.requestBody||'';
            const rs=document.createElement('h3'); rs.textContent='Raw response body';
            const rsp=document.createElement('pre'); rsp.textContent=d.responseBody||'(no bytes received)';
            details.appendChild(rq); details.appendChild(rqp);
            details.appendChild(rs); details.appendChild(rsp);
            box.appendChild(details);
          });
        }

        async function saveAiKey(value){
          const msg=$('s-aimsg');
          const r=await jpost('/api/ai/key',{key:value});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200
            ? (value?'Key saved. It is kept outside config.json and the file browser will '+
                     'not open it.':'Key forgotten.')
            : why(r);
          if(r.status===200){ $('s-aikey').value=''; showAi(); }
        }

        """;

    private static final String PARTSETTINGS = """

        // ---- Minecraft's own settings ----
        let props=[], propEdits={};

        async function loadProperties(){
          const box=$('sp-rows'); if(!box) return;
          propEdits={};
          const r=await jget('/api/properties');
          if(r.status!==200){
            box.innerHTML='<div class="note">'+esc((r.body&&r.body.error)||'unavailable')+
              '</div>';
            props=[]; paintSaveState(); return;
          }
          props=r.body.rows||[];
          paintProperties();
        }

        function paintProperties(){
          const box=$('sp-rows'); if(!box) return;
          const find=(($('sp-find')||{}).value||'').trim().toLowerCase();
          box.innerHTML='';
          const shown=props.filter(p=>!find || p.key.toLowerCase().includes(find) ||
            String(p.value).toLowerCase().includes(find));
          if(!shown.length){
            box.innerHTML='<div class="note">'+(props.length?'Nothing matches.'
              :'server.properties is empty.')+'</div>';
            paintSaveState(); return;
          }
          for(const p of shown) box.appendChild(propRow(p));
          paintSaveState();
        }

        function propRow(p){
          const row=document.createElement('div');
          row.className='sprow'+(p.key in propEdits?' edited':'');
          const name=document.createElement('code');
          name.textContent=p.key;
          row.appendChild(name);
          const now=(p.key in propEdits)?propEdits[p.key]:p.value;
          let input;
          if(p.type==='BOOL'){
            input=document.createElement('select');
            for(const v of ['true','false']){
              const o=document.createElement('option'); o.value=v; o.textContent=v;
              if(String(now)===v) o.selected=true;
              input.appendChild(o);
            }
          } else {
            input=document.createElement('input');
            input.type=p.secret?'password':(p.type==='INT'?'number':'text');
            input.value=now;
            if(p.secret) input.placeholder='unchanged';
          }
          input.oninput=input.onchange=()=>{
            const v=String(input.value);
            // Back to what it was is not a change, so the count stays honest.
            if(v===String(p.value)) delete propEdits[p.key];
            else propEdits[p.key]=v;
            row.className='sprow'+(p.key in propEdits?' edited':'');
            paintSaveState();
          };
          row.appendChild(input);
          const undo=document.createElement('button');
          undo.className='btn'; undo.textContent='Undo';
          undo.title='Put this one back';
          undo.onclick=()=>{ delete propEdits[p.key]; paintProperties(); };
          row.appendChild(undo);
          return row;
        }

        function paintSaveState(){
          const save=$('sp-save'); if(!save) return;
          const n=Object.keys(propEdits).length;
          save.disabled=n===0;
          save.textContent=n?('Save '+n+' change'+(n===1?'':'s')):'Save changes';
        }

        async function saveProperties(){
          const msg=$('sp-msg');
          const n=Object.keys(propEdits).length;
          if(!n) return;
          const r=await jpost('/api/properties',{set:propEdits});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.status===200
            ? ((r.body.changed||0)+' written to server.properties. '+
               'The server reads it when it boots, so restart for it to take effect.')
            : why(r);
          if(r.status===200) loadProperties();
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

        /**
         * Two lists, and they are not the same kind of thing.
         *
         * <p>The top one is this machine: jars in the server's own
         * <code>mods/</code> folder, which the server loads at start. The
         * bottom one is other people's machines: suggestions sent to joining
         * players, which they can decline. For a long time only the second one
         * existed, under the heading "Mods", and "add a mod" therefore meant
         * something quite different from what most people came here to do.
         */
        function modsPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML=
            '<p class="muted">Two separate lists: what this server runs, and what it '+
            'suggests to the people who join it.</p>'+
            '<section>'+
            '<div class="bartitle">'+
              '<h2 id="sm-count">On this server</h2>'+
              '<span class="spacer"></span>'+
              '<button class="btn go" id="sm-add">'+ICON.plus+' Install a mod</button>'+
            '</div>'+
            '<p class="muted">Jars in <code>mods/</code>, loaded by this server itself. '+
            'Nothing here is hot-loaded \u2014 adding, removing or turning one off takes '+
            'effect at the next start.</p>'+
            '<div class="browser" id="srvmodlist"></div>'+
            '<div class="msg" id="sm-msg"></div>'+
            '</section>'+
            '<section style="margin-top:20px">'+
            '<div class="bartitle">'+
              '<h2 id="m-count">Offered to players</h2>'+
              '<span class="spacer"></span>'+
              '<button class="btn go" id="m-add">'+ICON.plus+' Offer a mod</button>'+
              '<button class="btn cog" id="m-cog" title="Settings for offering mods">'+
                ICON.cog+'</button>'+
            '</div>'+
            '<p class="muted">Client mods this server suggests when someone joins. They run '+
            'on the player\u2019s computer, not this one, and nothing is installed without '+
            'that player agreeing to it.</p>'+
            '<div id="m-settings"></div>'+
            '<div class="browser" id="modlist"></div>'+
            '<div id="m-unused" style="margin-top:14px"></div>'+
            '<div class="msg" id="m-msg"></div>'+
            '</section>'+
            '<div id="m-restricted" style="margin-top:20px"></div>';
          setTimeout(()=>{
            $('sm-add').onclick=()=>menuUnder($('sm-add'),addServerModMenu());
            $('srvmodlist').oncontextmenu=e=>{
              if(e.target.closest('.modrow')) return;
              menuAt(e,addServerModMenu());
            };
            $('m-add').onclick=()=>menuUnder($('m-add'),addModMenu());
            $('m-cog').onclick=()=>{ modSettingsOpen=!modSettingsOpen; renderModSettings(); };
            $('modlist').oncontextmenu=e=>{
              if(e.target.closest('.modrow')) return;
              menuAt(e,addModMenu());
            };
            loadServerMods();
            loadMods();
          },0);
          return wrap;
        }

        /** The three ways of offering a mod, all landing on the one list. */
        function addModMenu(){
          return [
            {header:'Offer a mod to players'},
            {label:'Search Modrinth…',icon:ICON.globe,hint:'easiest',
             run:()=>modrinthDialog('offer')},
            {label:'Upload a jar…',icon:ICON.box,hint:'hosted here',run:uploadModDialog},
            {label:'Advertise a link…',icon:ICON.edit,hint:'by hand',
             run:()=>editModDialog(null)}
          ];
        }

        /** The two ways of putting a jar in this server's own mods folder. */
        function addServerModMenu(){
          return [
            {header:'Install on this server'},
            {label:'Search Modrinth…',icon:ICON.globe,hint:'easiest',
             run:()=>modrinthDialog('server')},
            {label:'Upload a jar…',icon:ICON.box,hint:'from this computer',
             run:uploadServerModDialog}
          ];
        }

        // ---- mods this server runs ----
        let serverModsData=null;

        async function loadServerMods(){
          const box=$('srvmodlist'); if(!box) return;
          const r=await jget('/api/servermods');
          if(r.status!==200){
            box.innerHTML='<div class="fempty">'+esc(r.body.error||'unavailable')+'</div>';
            return;
          }
          serverModsData=r.body;
          const mods=r.body.mods||[];
          const count=$('sm-count');
          if(count) count.textContent='On this server'+(mods.length?' ('+mods.length+')':'');
          box.innerHTML='';
          if(!mods.length){
            box.innerHTML='<div class="fempty">Nothing in <code>mods/</code> \u2014 which '+
              'cannot be true while Almin is answering, so this server\u2019s folder is '+
              'somewhere else.</div>';
            return;
          }
          for(const m of mods) box.appendChild(serverModRow(m));
        }

        function serverModRow(m){
          const row=document.createElement('div'); row.className='modrow';
          const letter=document.createElement('span'); letter.className='modicon';
          letter.textContent=((m.name||m.file||'?').trim().charAt(0)||'?').toUpperCase();
          if(!m.enabled) letter.style.opacity='.45';
          row.appendChild(letter);
          const body=document.createElement('div'); body.className='body';
          // Three states worth telling apart: running, sitting there waiting
          // for a restart, and switched off. A list that only says "installed"
          // cannot answer "why is it not working".
          const state = !m.enabled ? '<span class="chip">Off</span>'
            : m.loaded ? '<span class="chip jar">Loaded</span>'
            : '<span class="chip req">Waiting for a restart</span>';
          body.innerHTML='<div class="ttl">'+esc(m.name||m.file)+
            (m.version?' <span class="muted" style="font-weight:400">'+esc(m.version)+
              '</span>':'')+' '+state+
            (m.ours?' <span class="chip">Almin</span>':'')+'</div>'+
            '<div class="sub" title="'+esc(m.file)+'">'+
              (m.id?'<code>'+esc(m.id)+'</code> · ':'')+esc(m.file)+
              (m.bytes?' · '+fmtBytes(m.bytes):'')+'</div>';
          const acts=document.createElement('div'); acts.className='acts';
          if(!m.ours){
            const flip=document.createElement('button'); flip.className='btn';
            flip.textContent=m.enabled?'Turn off':'Turn on';
            flip.onclick=()=>changeServerMod(m,m.enabled?'disable':'enable');
            const more=document.createElement('button'); more.className='btn cog';
            more.innerHTML='&#8943;'; more.title='More';
            more.onclick=()=>menuUnder(more,serverModMenu(m));
            acts.append(flip,more);
          } else {
            const note=document.createElement('span'); note.className='muted';
            note.style.fontSize='11.5px'; note.textContent='updated from the panel';
            acts.appendChild(note);
          }
          row.append(body,acts);
          if(!m.ours) row.oncontextmenu=ev=>menuAt(ev,serverModMenu(m));
          return row;
        }

        function serverModMenu(m){
          return [{header:m.name||m.file},
            {label:m.enabled?'Turn off':'Turn on',
             run:()=>changeServerMod(m,m.enabled?'disable':'enable')},
            'sep',
            {label:'Delete the jar…',icon:ICON.trash,danger:true,
             run:()=>deleteServerModDialog(m)}];
        }

        async function changeServerMod(m,action){
          const r=await jpost('/api/servermods/change',{file:m.file,action:action});
          const msg=$('sm-msg');
          if(msg){ msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent=(r.body&&(r.body.message||r.body.error))||'failed'; }
          loadServerMods();
        }

        function deleteServerModDialog(m){
          modal('Delete a jar',(body,close)=>{
            body.innerHTML='<p>Delete <code>'+esc(m.file)+'</code> from this server\u2019s '+
              '<code>mods/</code>?</p>'+
              '<p class="muted">The running server is unaffected until it restarts. If you '+
              'only want to test whether this mod is the problem, <b>Turn off</b> does the '+
              'same thing and can be undone.</p>'+
              '<div class="row2"><button class="btn danger" id="sd-go">Delete</button>'+
              '<button class="btn" id="sd-no">Cancel</button></div>'+
              '<div class="msg" id="sd-msg"></div>';
            $('sd-no').onclick=close;
            $('sd-go').onclick=async()=>{
              const r=await jpost('/api/servermods/change',{file:m.file,action:'delete'});
              if(r.status===200){ close(); loadServerMods();
                const msg=$('sm-msg');
                if(msg){ msg.className='msg ok'; msg.textContent=r.body.message||'Deleted.'; } }
              else { const x=$('sd-msg'); x.className='msg err';
                x.textContent=(r.body&&(r.body.error||r.body.message))||'delete failed'; }
            };
          });
        }

        function uploadServerModDialog(){
          modal('Install a jar on this server',(body,close)=>{
            body.innerHTML='<p class="muted">Goes into this server\u2019s <code>mods/</code> '+
              'folder. Fabric reads that folder once, while the game is starting, so the mod '+
              'does nothing until the server restarts \u2014 and a mod that will not start '+
              'takes the server with it, so restart when you can watch the console.</p>'+
              '<input type="file" id="sm-file" accept=".jar" multiple>'+
              '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:10px">'+
              '<input type="checkbox" id="sm-replace" style="width:auto"> '+
              'Replace a jar of the same name</label>'+
              '<div class="row2"><button class="btn go" id="sm-upgo">Install</button></div>'+
              '<div class="msg" id="sm-upmsg"></div>';
            $('sm-upgo').onclick=()=>uploadServerMods(close);
          });
        }

        async function uploadServerMods(close){
          const inp=$('sm-file'), msg=$('sm-upmsg'), btn=$('sm-upgo');
          if(!inp.files||!inp.files.length){
            msg.className='msg err'; msg.textContent='Choose a .jar first.'; return; }
          btn.disabled=true;
          const done=[]; let failed='';
          const replace=$('sm-replace').checked?'&replace=1':'';
          for(const f of inp.files){
            msg.className='msg'; msg.textContent='Installing '+f.name+'…';
            try{
              const r=await fetch('/api/servermods/upload?name='+encodeURIComponent(f.name)+replace,
                {method:'POST',credentials:'same-origin',
                 headers:{'Content-Type':'application/octet-stream'},body:f});
              const b=await r.json().catch(()=>({}));
              if(r.status!==200){ failed=f.name+': '+(b.error||b.message||'install failed'); break; }
              done.push(b.message||f.name);
            }catch(e){ failed=f.name+': install failed — '+e.message; break; }
          }
          btn.disabled=false;
          loadServerMods();
          if(failed){ msg.className='msg err'; msg.textContent=failed; return; }
          close();
          const out=$('sm-msg');
          if(out){ out.className='msg ok'; out.textContent=done.join(' '); }
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

        /**
         * Mods players are asked not to run.
         *
         * <p>Lives below the list it is about rather than inside the cog above
         * it. It is a list in its own right — the third one on this page — and
         * hiding a list inside a settings toggle is how it never got found.
         *
         * <p>Still gated on the Almin client mod being required, because
         * without it there is no mod list to check and the rule would only
         * ever land on whoever was honest enough to be visible.
         * <code>mods-show-restricted</code> puts it back for anyone who wants
         * it anyway.
         */
        function renderRestricted(){
          const box=$('m-restricted'); if(!box || !modsData) return;
          box.innerHTML='';
          box.appendChild(restrictedSection());
        }

        function restrictedSection(){
          const sec=document.createElement('section');
          sec.style.margin='0 0 13px';
          const on=modsData.requireClientMod || modsData.showRestricted;
          if(!on){
            sec.innerHTML='<h2>Restricted mods</h2>'+
              '<div class="note">Requires <b>Almin required to play</b>. Without the client '+
              'mod there is no mod list to check, so the rule would only ever catch the '+
              'players honest enough to be visible \u2014 which is the wrong half. '+
              'Turn that on above, or set <code>mods-show-restricted</code> to show this '+
              'anyway.</div>';
            return sec;
          }
          const list=(modsData.restricted||'').split(',').map(x=>x.trim()).filter(Boolean);
          sec.innerHTML='<h2>Restricted mods</h2>'+
            '<p class="muted">Mod ids, as the loader spells them \u2014 '+
            '<code>xaerominimap</code>, not <code>Xaero\u2019s Minimap</code>. Checked '+
            'against what each client reports at join, which is self-reported: this is a '+
            'house rule, not an anti-cheat.</p>'+
            '<div class="chips" id="m-banned"></div>'+
            '<div class="term" style="margin-top:10px">'+
            '<input id="m-ban" placeholder="mod id to restrict">'+
            '<button class="btn" id="m-banadd">Restrict</button></div>'+
            '<div class="msg" id="m-banmsg"></div>';
          setTimeout(()=>{
            const chips=$('m-banned');
            if(chips){
              if(!list.length){
                chips.innerHTML='<span class="muted" style="font-size:12.5px">'+
                  'Nothing is restricted.</span>';
              }
              for(const id of list){
                const b=document.createElement('button');
                b.className='on';
                b.innerHTML=esc(id)+' <b>\u00d7</b>';
                b.title='Stop restricting '+id;
                b.onclick=()=>setRestricted(list.filter(x=>x!==id));
                chips.appendChild(b);
              }
            }
            const add=()=>{
              const v=($('m-ban').value||'').trim().toLowerCase();
              if(!v) return;
              if(list.indexOf(v)>=0){ $('m-ban').value=''; return; }
              setRestricted(list.concat([v]));
            };
            $('m-banadd').onclick=add;
            $('m-ban').onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); add(); } };
            const kick=cfgToggle('mods-restricted-kick',
              'Disconnect players running one',modsData.restrictedKick,loadMods);
            sec.appendChild(kick);
          },0);
          return sec;
        }

        async function setRestricted(ids){
          const msg=$('m-banmsg');
          const r=await jpost('/api/config',{name:'mods-restricted',value:ids.join(',')});
          if(msg){
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent=r.status===200?'Saved.':((r.body&&r.body.error)||'failed');
          }
          loadMods();
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

        /**
         * @param where 'offer' to advertise it to players, 'server' to install
         *              it here. The same search, and two quite different acts
         *              at the end of it — so the dialog says which one it is
         *              in its title and in its wording.
         */
        function modrinthDialog(where){
          const here=where==='server';
          modal(here?'Install from Modrinth':'Offer a mod from Modrinth',(body)=>{
            body.innerHTML='<p class="muted">Almin downloads the build that fits the Minecraft '+
              'version this server runs and reads the mod id out of the jar, which is the part '+
              'that is easy to get wrong by hand. '+
              (here?'It goes into this server\u2019s <code>mods/</code> folder and loads at the '+
                    'next start.'
                  :'It is offered to players, who can decline it.')+
              ' Search, or paste a link like '+
              '<code>https://modrinth.com/mod/modmenu</code>.</p>'+
              '<div class="term"><input id="mr-q" '+
                'placeholder="search Modrinth, or paste a project link">'+
              '<button class="btn" id="mr-go">Search</button>'+
              '<button class="btn go" id="mr-add">'+(here?'Install link':'Add link')+
              '</button></div>'+
              (here?''
                  :'<label class="muted" style="display:flex;gap:8px;align-items:center;'+
                   'margin-top:9px"><input type="checkbox" id="mr-req" style="width:auto"> '+
                   'Mark anything added as required</label>')+
              '<div class="msg" id="mr-msg"></div>'+
              '<div id="mr-hits"></div>';
            $('mr-go').onclick=()=>searchModrinth(where);
            $('mr-add').onclick=()=>addModrinth($('mr-q').value.trim(),where);
            $('mr-q').onkeydown=e=>{
              if(e.key==='Enter'){ e.preventDefault(); searchModrinth(where); } };
            $('mr-q').focus();
          },{wide:true});
        }

        async function searchModrinth(where){
          const q=$('mr-q').value.trim(), msg=$('mr-msg'), box=$('mr-hits');
          if(!q){ msg.className='msg err'; msg.textContent='Type something to search for.'; return; }
          // A pasted link is not a search; it is the thing itself.
          if(/modrinth\\.com\\//i.test(q)) return addModrinth(q,where);
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
            add.textContent=where==='server'?'Install':'Offer';
            add.onclick=()=>addModrinth(h.slug,where);
            acts.appendChild(add);
            row.append(body,acts);
            list.appendChild(row);
          }
          box.appendChild(list);
        }
        async function addModrinth(link,where){
          const msg=$('mr-msg');
          const here=where==='server';
          if(!link){ msg.className='msg err'; msg.textContent='Paste a link or search first.'; return; }
          msg.className='msg'; msg.textContent='Fetching '+link+'…';
          const req=$('mr-req');
          const r=await jpost('/api/mods/modrinth',
            {action:here?'server':'add',link:link,required:!!(req&&req.checked)});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent=r.body.message||r.body.error||'failed';
          if(r.status===200){ $('mr-q').value=''; if(here) loadServerMods(); else loadMods(); }
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
          if(count) count.textContent='Offered to players'+(mods.length?' ('+mods.length+')':'');
          box.innerHTML='';
          if(!mods.length){
            box.innerHTML='<div class="fempty">Nothing advertised yet — '+
              'use <b>Add mod</b>, or right-click here.</div>';
          } else {
            for(const m of mods) box.appendChild(modRow(m));
          }
          paintUnusedJars(r.body.unusedFiles||[]);
          renderModSettings();
          renderRestricted();
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
          else if(tab==='activity'){ loadActivity(); liveTick(); }
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
    static final String HTML = String.join("", PART1, PARTFILES, PART2, PARTMAP, PARTSEQ,
        PARTMAPUI, PARTINSIGHT, PARTSCENE, PARTBLUE, PART3, PARTSETTINGS);
}
