#!/usr/bin/env python3
"""Build using the verified local Android SDK; no remote dependencies."""
from pathlib import Path
import os, subprocess, zipfile, sys, shutil
root = Path(__file__).resolve().parent
sys.path.insert(0, str(root.parent / 'tools'))
from android_build_env import java_home, sdk_paths
sdk, bt, android = sdk_paths(root.parent)
java = java_home()
build = root / 'build'
build.mkdir(exist_ok=True)
classes = build / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
env = dict(os.environ, JAVA_HOME=str(java))
def run(*args): subprocess.run([str(a) for a in args], cwd=root, env=env, check=True)
run(java/'bin/javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', android, '-d', classes, *sorted((root/'src').rglob('*.java')), *sorted((root/'stubs').rglob('*.java')))
# Xposed headers are compile-only; Vector supplies the real classes at runtime.
run(bt/'d8', '--lib', android, '--classpath', classes, '--min-api', '26', '--output', build, *sorted((classes/'local').rglob('*.class')))
run(bt/'aapt', 'package', '-f', '-M', root/'AndroidManifest.xml', '-A', root/'assets', '-I', android, '-F', build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(build/'classes.dex', 'classes.dex')
run(bt/'zipalign', '-f', '4', build/'unsigned.apk', build/'aligned.apk')
keystore = root/'local-signing.p12'
if not keystore.exists():
    run(java/'bin/keytool', '-genkeypair', '-keystore', keystore, '-storepass', 'local-build-only', '-keypass', 'local-build-only', '-alias', 'boox', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=Local BOOX OpenAI Setup')
    keystore.chmod(0o600)
run(bt/'apksigner', 'sign', '--ks', keystore, '--ks-pass', 'pass:local-build-only', '--out', build/'boox-openai-setup.apk', build/'aligned.apk')
run(bt/'apksigner', 'verify', '--verbose', build/'boox-openai-setup.apk')
