# run_once.py  — execute in your project root
from huggingface_hub import hf_hub_download
import shutil

path = hf_hub_download(
    repo_id="google/siglip2-base-patch16-224", filename="spiece.model"
)
shutil.copy(path, "app/src/main/assets/spiece.model")
print("Done →", path)
