"""Resolve explicit or locally installed Android build tools without downloads."""
import os
from pathlib import Path
import shutil
import subprocess


def java_home():
    if os.environ.get("JAVA_HOME"):
        home = Path(os.environ["JAVA_HOME"])
    elif Path("/usr/libexec/java_home").exists():
        home = Path(subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    else:
        compiler = shutil.which("javac")
        if not compiler:
            raise RuntimeError("Install JDK 17 and set JAVA_HOME.")
        home = Path(compiler).resolve().parents[1]
    if not (home / "bin/javac").is_file():
        raise RuntimeError("JAVA_HOME must contain bin/javac.")
    return home


def sdk_paths(project):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or project / "tools/android-sdk")
    platform = sdk / "platforms/android-35/android.jar"
    if not platform.is_file():
        platform = sdk / "android-35/android.jar"
    # Support both the original extracted archive layout and sdkmanager's layout.
    tools = Path(os.environ.get("BOOX_ANDROID_BUILD_TOOLS") or sdk / "build-tools/35.0.0")
    if not tools.is_dir() and not os.environ.get("BOOX_ANDROID_BUILD_TOOLS"):
        tools = sdk / "android-15"
    for path in (platform, tools / "apksigner", tools / "d8", tools / "aapt", tools / "zipalign"):
        if not path.is_file():
            raise RuntimeError(f"Missing Android tool: {path}. See docs/reproduction/acquisition.json.")
    return sdk.resolve(), tools.resolve(), platform.resolve()


def gradle_path(project):
    configured = os.environ.get("BOOX_GRADLE")
    path = Path(configured) if configured else project / "tools/gradle-8.11.1/bin/gradle"
    if not path.is_file():
        raise RuntimeError("Install Gradle 8.11.1; set BOOX_GRADLE to its executable.")
    return path.resolve()
