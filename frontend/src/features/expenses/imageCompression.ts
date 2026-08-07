export const RECEIPT_MAX_EDGE = 1600;

export type CompressedReceipt = {
  blob: Blob;
  width: number;
  height: number;
};

function scaledSize(width: number, height: number) {
  const scale = Math.min(1, RECEIPT_MAX_EDGE / Math.max(width, height));
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

export async function compressReceiptImage(
  file: File,
): Promise<CompressedReceipt> {
  if (!file.type.startsWith("image/")) {
    throw new Error("画像ファイルを選んでください。");
  }

  const bitmap = await createImageBitmap(file, {
    imageOrientation: "from-image",
  });
  try {
    const size = scaledSize(bitmap.width, bitmap.height);
    const canvas = document.createElement("canvas");
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("この端末では画像を処理できません。");
    context.drawImage(bitmap, 0, 0, size.width, size.height);

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", 0.82),
    );
    if (!blob) throw new Error("画像を圧縮できませんでした。");
    return { blob, ...size };
  } finally {
    bitmap.close();
  }
}
