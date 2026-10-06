import tempfile
from pathlib import Path

from fpdf import FPDF
from PIL import Image, ImageDraw, ImageFont

from .adb import AdbDevice

_BUNDLED_FONT = Path(__file__).resolve().parent / "fonts" / "DejaVuSans.ttf"

_PAGE_W, _PAGE_H = 595, 842


def generate_pdf(path: str, pages: int):
    pdf = FPDF(unit="pt", format="A4")
    pdf.add_font("DejaVuSans", "", str(_BUNDLED_FONT))
    for i in range(pages):
        pdf.add_page()
        pdf.set_fill_color(255, 255, 255)
        pdf.rect(0, 0, _PAGE_W, _PAGE_H, style="F")
        pdf.set_text_color(0, 0, 0)
        pdf.set_font("DejaVuSans", size=14)
        pdf.text(40, 60, f"Page {i+1} of {pages} - test content")
    pdf.output(path)


def generate_image_pdf(path: str, pages: int):
    """Generate a PDF whose pages carry *no* text layer.

    Each page is a raster image (text drawn into a JPEG) embedded into the PDF.
    A PicPocket import detects no extractable text and rasterizes each page to
    its own JPEG file, so one page == one file. Used by sync scenarios that need
    per-page files (push/prune and interrupted multi-file upload), which the
    born-digital (single shared file) storage model no longer provides.
    """
    pdf = FPDF(unit="pt", format="A4")
    font = ImageFont.truetype(str(_BUNDLED_FONT), 16)
    for i in range(pages):
        img = Image.new("RGB", (_PAGE_W, _PAGE_H), "white")
        draw = ImageDraw.Draw(img)
        draw.text((40, 60), f"Page {i+1} of {pages} - test content", fill="black", font=font)
        img_tmp = Path(tempfile.mkstemp(suffix=".jpg")[1])
        try:
            img.save(str(img_tmp), "JPEG", quality=85)
            pdf.add_page()
            pdf.image(str(img_tmp), 0, 0, _PAGE_W, _PAGE_H)
        finally:
            img_tmp.unlink(missing_ok=True)
    pdf.output(path)


def generate_and_push(adb: AdbDevice, filename_stem: str, pages: int = 1) -> str:
    return _push_generated(adb, filename_stem, pages, image_only=False)


def generate_image_and_push(adb: AdbDevice, filename_stem: str, pages: int = 1) -> str:
    """Like generate_and_push but produces an image-only (rasterized) PDF."""
    return _push_generated(adb, filename_stem, pages, image_only=True)


def _push_generated(adb: AdbDevice, filename_stem: str, pages: int, image_only: bool) -> str:
    tmp = Path(tempfile.mkstemp(suffix=".pdf")[1])
    try:
        if image_only:
            generate_image_pdf(str(tmp), pages)
        else:
            generate_pdf(str(tmp), pages)
        remote = f"/sdcard/Download/{filename_stem}.pdf"
        adb.push(str(tmp), remote)
        return f"{filename_stem}.pdf"
    finally:
        tmp.unlink(missing_ok=True)
