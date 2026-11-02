"""Opt-in bounded Gmail verifier. Compile-only unless --live is passed.
Uses existing packaged libraries and normal app configuration. No app workers,
mailbox writes, migrations, model calls, credential output or body files.
"""
from pathlib import Path
from datetime import datetime,timezone
from zipfile import ZipFile
import subprocess,sys
root=Path(__file__).resolve().parents[2]
cp_file=Path('/tmp/tidymail-probe-classpath')
if not cp_file.exists():
    libs=Path('/tmp/tidymail-evidence-libs');libs.mkdir(exist_ok=True)
    with ZipFile(root/'target/tidymail-0.1.0.jar') as archive:
        for member in archive.namelist():
            if member.startswith('BOOT-INF/lib/') and member.endswith('.jar'):
                (libs/Path(member).name).write_bytes(archive.read(member))
    cp_file.write_text(str(root/'target/classes')+':'+':'.join(str(p) for p in sorted(libs.glob('*.jar'))))
classpath=cp_file.read_text().strip()
classes=Path('/tmp/tidymail-evidence-classes');classes.mkdir(exist_ok=True)
subprocess.run(['javac','-cp',classpath,'-d',str(classes),str(root/'tools/verification/GmailEvidenceProbe.java')],check=True)
if '--live' in sys.argv and '--compile-only' not in sys.argv:
    parser_python=Path('/tmp/tidymail-evidence-venv/bin/python')
    if not parser_python.exists():
        raise SystemExit('Temporary calendar-parser environment is required; see tools/verification/README.md')
    mode='--read-only-cost-check' if '--cost-check' in sys.argv else '--read-only-sample'
    name='email-cost-check-results.json' if '--cost-check' in sys.argv else 'email-capability-results.json'
    output=root/'docs/verification/2026-11-01'/name
    if output.exists():
        output=output.with_name(output.stem+'-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.json')
    subprocess.run(['java','-cp',str(classes)+':'+classpath,'GmailEvidenceProbe',mode,str(output),str(root/'tools/verification/parse_calendar_probe.py')],cwd=root,check=True)
else:
    print('Compile-only completed. --live explicitly enables the bounded Gmail reads.')
