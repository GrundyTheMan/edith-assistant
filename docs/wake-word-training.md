# Training the "Hey Edith" wake word

Hands-free mode (C14) listens for "Hey Edith" with [openWakeWord](https://github.com/dscripka/openWakeWord). The
phrase needs its own small model, trained once from synthetic voices in openWakeWord's Google Colab notebook. It
takes about an hour, mostly waiting. You need a Google account. Nothing is installed on your PC.

Until the model exists, Edith uses openWakeWord's ready-made "Hey Jarvis" model as a stand-in.

## Steps

1. Open the notebook straight from openWakeWord's GitHub (not a copy someone shared):
   <https://colab.research.google.com/github/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb>
2. Sign in to Google if asked. Colab warns that the notebook isn't from Google: choose **Run anyway**.
3. **Runtime → Change runtime type → T4 GPU → Save.**
4. Run the cells from the top, one at a time (▶ on each cell), until you reach the cell that sets
   `config["target_phrase"]`. The early cells download several GB of audio; that's expected. If a cell fails, run
   it again once; if it still fails, copy the last 20 lines of its output and send them to me.
5. In that cell, change these lines before running it:
   ```python
   config["target_phrase"] = ["hey edith"]
   config["model_name"] = "hey_edith"
   config["n_samples"] = 3000
   config["n_samples_val"] = 1000
   config["steps"] = 20000
   config["custom_negative_phrases"] = ["edit", "hey edit", "hey eddie", "edith", "hey ed", "head with"]
   ```
   If `model_name` or `custom_negative_phrases` aren't in the cell, add them as new lines.
   The negative phrases teach it what *not* to wake on.
6. Run the remaining cells in order. **Generate clips** says it takes about 10 minutes; **augment** and **train**
   take longer. Keep the tab open; Colab stops a free session that's left idle too long.
7. When training ends, open the **Files** panel (folder icon on the left) → `my_custom_model` → right-click
   **`hey_edith.onnx`** → **Download**. (Ignore the `.tflite` file; Edith uses ONNX.)
8. Save it as:
   ```
   D:\Dev Projects\edith\app\src\main\assets\wakeword\hey_edith.onnx
   ```
   Then tell me it's there. I'll check the file (a few hundred KB) and switch Edith to it.

## Good to know
- **Licence:** openWakeWord's code is Apache 2.0. Its shared audio models (used by every wake word, including
  this one) are CC BY-NC-SA 4.0: fine for a personal app, not for selling one.
- **Accuracy** can only be judged on a real phone in a real room. If it wakes too often or too rarely, Settings →
  Hands-free has a sensitivity slider; retraining with more samples (`n_samples` 5000) also helps.
- Nothing you say is uploaded while training: the notebook uses synthetic voices only.
