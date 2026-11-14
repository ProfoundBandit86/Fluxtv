# Quick test: only the search listening screen
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell am start -W -n $P/.SearchActivity --ez demo_listen true; sleep 3; shot q1-demo; texts q1 20
adb shell am start -W -n $P/.SearchActivity --ez voice true; sleep 2; shot q2-voice; top q2; sleep 4; shot q3-voice-later; texts q3 20
adb logcat -d | grep -E "FluxSearch|AndroidRuntime|SpeechRecognizer|RecognitionService" | tail -40
