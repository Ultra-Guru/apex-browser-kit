import shutil, subprocess, sys, tempfile
from pathlib import Path
SRC = Path(__file__).resolve().parent.parent.parent  # repo root
res=[]
def run(repo): return subprocess.run([sys.executable,str(repo/"scripts/check_code_quality.py")],capture_output=True,text=True,cwd=repo)
def case(name,mut,expect):
    with tempfile.TemporaryDirectory() as t:
        repo=Path(t)/"r"; shutil.copytree(SRC,repo,ignore=shutil.ignore_patterns(".git","build",".gradle",".kotlin"))
        mut(repo); p=run(repo); o=p.stdout+p.stderr
        ok=p.returncode!=0 and expect in o; res.append((ok,name))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print(f"       exit={p.returncode} expect={expect!r}")
            for l in o.splitlines()[:6]: print("       "+l)
def case_ok(name,mut,expect):
    # 反向锁：违规不存在时门禁必须放行（防止口径修正后把合法仓库误杀）。
    with tempfile.TemporaryDirectory() as t:
        repo=Path(t)/"r"; shutil.copytree(SRC,repo,ignore=shutil.ignore_patterns(".git","build",".gradle",".kotlin"))
        mut(repo); p=run(repo); o=p.stdout+p.stderr
        ok=p.returncode==0 and expect in o; res.append((ok,name))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print(f"       exit={p.returncode} expect={expect!r}")
            for l in o.splitlines()[:6]: print("       "+l)
def godfile(repo):
    f=repo/"browser-core/src/main/kotlin/com/apex/browser/core/JsLiteral.kt"
    # v1.3.0 起预算按非注释行计：注入体必须是代码行，注释行不再触发 GATE 1。
    f.write_text(f.read_text(encoding="utf-8")+("\nfun pad_decl_2026(){}"*1300),encoding="utf-8")
case("catches a main-source God file (size budget)",godfile,"GATE 1")
def docfile(repo):
    f=repo/"browser-core/src/main/kotlin/com/apex/browser/core/JsLiteral.kt"
    # KDoc/注释增长不占代码预算（口径修正的显式锁；注意不得引入空行——空行是非注释行）。
    f.write_text(f.read_text(encoding="utf-8")+("\n// doc-only line")*1300,encoding="utf-8")
case_ok("comment-only growth does not trip the budget (KDoc is free)",docfile,"PASS GATE 1")
def st(repo):
    f=repo/"browser-core/src/main/kotlin/com/apex/browser/core/JsLiteral.kt"
    f.write_text(f.read_text(encoding="utf-8")+"\nfun boom(){ try{}catch(e:Throwable){ e.printStackTrace() } }\n",encoding="utf-8")
case("catches printStackTrace() in main sources",st,"GATE 2")
def rf(repo):
    f=repo/"browser-core/src/main/kotlin/com/apex/browser/core/JsLiteral.kt"
    f.write_text(f.read_text(encoding="utf-8")+"\nfun d(x:Any)=x.javaClass.getMethod(\"foo\")\n",encoding="utf-8")
case("catches javaClass.getMethod reflective dispatch",rf,"GATE 3")
ok=sum(1 for r,_ in res if r); print(f"\n{ok}/{len(res)} code-quality negative-tests correct"); sys.exit(0 if ok==len(res) else 1)
