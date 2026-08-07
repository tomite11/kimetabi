import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useForm } from "react-hook-form";
import { useParams } from "react-router";
import { z } from "zod";

import { createAmountExpenseDraft } from "./expenseApi";
import {
  compressReceiptImage,
  type CompressedReceipt,
} from "./imageCompression";
import styles from "./ExpenseCapturePage.module.css";

const amountSchema = z.object({
  amount: z.coerce
    .number({ invalid_type_error: "金額を入力してください。" })
    .int("1円単位で入力してください。")
    .min(1, "1円以上を入力してください。"),
});
type AmountForm = z.infer<typeof amountSchema>;
type Mode = "photo" | "amount";

export function ExpenseCapturePage() {
  const tripId = Number(useParams().tripId);
  const [mode, setMode] = useState<Mode>("photo");
  const [receipt, setReceipt] = useState<CompressedReceipt>();
  const [previewUrl, setPreviewUrl] = useState<string>();
  const [imageError, setImageError] = useState<string>();
  const [isCompressing, setIsCompressing] = useState(false);
  const amountInputRef = useRef<HTMLInputElement | null>(null);
  const cameraInputRef = useRef<HTMLInputElement | null>(null);
  const libraryInputRef = useRef<HTMLInputElement | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<AmountForm>({ resolver: zodResolver(amountSchema) });
  const amountRegistration = register("amount");
  const mutation = useMutation({
    mutationFn: (values: AmountForm) =>
      createAmountExpenseDraft(tripId, values.amount),
  });

  useEffect(
    () => () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    },
    [previewUrl],
  );

  useEffect(() => {
    if (mode === "amount") amountInputRef.current?.focus();
  }, [mode]);

  function chooseMode(nextMode: Mode) {
    setMode(nextMode);
    mutation.reset();
  }

  async function handleImage(file?: File) {
    if (!file) return;
    setImageError(undefined);
    setIsCompressing(true);
    try {
      const compressed = await compressReceiptImage(file);
      setReceipt(compressed);
      setPreviewUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return URL.createObjectURL(compressed.blob);
      });
    } catch (error) {
      setReceipt(undefined);
      setImageError(
        error instanceof Error ? error.message : "画像を圧縮できませんでした。",
      );
    } finally {
      setIsCompressing(false);
    }
  }

  function openFilePickerOnKeyboard(
    event: KeyboardEvent<HTMLLabelElement>,
    input: HTMLInputElement | null,
  ) {
    if (event.key !== "Enter" && event.key !== " ") return;
    event.preventDefault();
    input?.click();
  }

  return (
    <section className={styles.page} aria-labelledby="expense-capture-title">
      <header className={styles.heading}>
        <p className={styles.eyebrow}>QUICK CAPTURE</p>
        <h2 className={styles.title} id="expense-capture-title">
          支出を記録する
        </h2>
        <p className={styles.lead}>
          レジ前では写真だけでも大丈夫。レシートがなければ金額から始められます。
        </p>
      </header>

      <div className={styles.choices} role="group" aria-label="記録方法">
        <button
          className={styles.choice}
          data-active={mode === "photo"}
          type="button"
          aria-pressed={mode === "photo"}
          onClick={() => chooseMode("photo")}
        >
          <span className={styles.choiceIcon} aria-hidden="true">
            ▣
          </span>
          <strong>レシートを撮る</strong>
          <span>写真だけで保存準備</span>
        </button>
        <button
          className={styles.choice}
          data-active={mode === "amount"}
          type="button"
          aria-pressed={mode === "amount"}
          onClick={() => chooseMode("amount")}
        >
          <span className={styles.choiceIcon} aria-hidden="true">
            ¥
          </span>
          <strong>金額を入れる</strong>
          <span>レシートなし</span>
        </button>
      </div>

      {mode === "photo" ? (
        <div className={styles.panel} aria-live="polite">
          <input
            ref={cameraInputRef}
            className={styles.hiddenInput}
            id="receipt-camera"
            type="file"
            tabIndex={-1}
            accept="image/*"
            capture="environment"
            onChange={(event) => void handleImage(event.target.files?.[0])}
          />
          <label
            className={styles.fileAction}
            htmlFor="receipt-camera"
            role="button"
            tabIndex={0}
            onKeyDown={(event) =>
              openFilePickerOnKeyboard(event, cameraInputRef.current)
            }
          >
            カメラで撮影
          </label>
          <input
            ref={libraryInputRef}
            className={styles.hiddenInput}
            id="receipt-file"
            type="file"
            tabIndex={-1}
            accept="image/jpeg,image/png,image/webp"
            onChange={(event) => void handleImage(event.target.files?.[0])}
          />
          <label
            className={`${styles.fileAction} ${styles.fileActionSecondary}`}
            htmlFor="receipt-file"
            role="button"
            tabIndex={0}
            onKeyDown={(event) =>
              openFilePickerOnKeyboard(event, libraryInputRef.current)
            }
          >
            写真ライブラリから選ぶ
          </label>
          {isCompressing ? (
            <p className={styles.status}>画像を軽くしています…</p>
          ) : null}
          {imageError ? (
            <p className={styles.error} role="alert">
              {imageError}
            </p>
          ) : null}
          {receipt && previewUrl ? (
            <>
              <img
                className={styles.preview}
                src={previewUrl}
                alt="圧縮したレシートのプレビュー"
              />
              <p className={styles.success} role="status">
                保存準備ができました（{receipt.width}×{receipt.height}px・
                {Math.ceil(receipt.blob.size / 1024)}
                KB）。この画面を閉じずに続けてください。
              </p>
            </>
          ) : null}
        </div>
      ) : (
        <form
          className={styles.panel}
          onSubmit={handleSubmit((values) => mutation.mutate(values))}
          noValidate
        >
          <label className={styles.amountLabel} htmlFor="expense-amount">
            支出の総額
          </label>
          <div className={styles.amountField}>
            <span aria-hidden="true">¥</span>
            <input
              {...amountRegistration}
              ref={(element) => {
                amountRegistration.ref(element);
                amountInputRef.current = element;
              }}
              id="expense-amount"
              type="number"
              inputMode="numeric"
              min="1"
              step="1"
              placeholder="0"
              aria-describedby={errors.amount ? "amount-error" : undefined}
            />
          </div>
          {errors.amount ? (
            <p className={styles.error} id="amount-error" role="alert">
              {errors.amount.message}
            </p>
          ) : null}
          {mutation.isError ? (
            <p className={styles.error} role="alert">
              記録できませんでした。通信状態を確認して、もう一度お試しください。
            </p>
          ) : null}
          {mutation.isSuccess ? (
            <p className={styles.success} role="status">
              金額を未確定の支出として記録しました。あとで支払った人と割り勘を確認できます。
            </p>
          ) : null}
          <button
            className={styles.submit}
            type="submit"
            disabled={mutation.isPending}
          >
            {mutation.isPending ? "記録しています…" : "未確定として記録"}
          </button>
        </form>
      )}
    </section>
  );
}
