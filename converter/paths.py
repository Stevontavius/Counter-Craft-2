"""Where the converter extracts CS2 / CS:GO files: cs2-extract/ next to the converter, or CC2_EXTRACT (the installer
builds in a throwaway folder there and deletes it afterwards, so only the finished pack stays on disk)."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTRACT = Path(os.environ["CC2_EXTRACT"]) if os.environ.get("CC2_EXTRACT") else ROOT / "cs2-extract"
