package com.quanlybanhang.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tiện ích chuyển đổi số tiền (BigDecimal/Long) thành chữ tiếng Việt chuẩn.
 * Ví dụ: 25,450,000 -> "Hai mươi lăm triệu bốn trăm năm mươi nghìn đồng chẵn"
 */
public final class VietnameseNumberToWords {

    private static final String[] DIGITS = {
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    };

    private static final String[] UNITS = {
            "", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ"
    };

    private VietnameseNumberToWords() {
    }

    public static String convertToWords(BigDecimal amount) {
        if (amount == null) {
            return "Không đồng";
        }
        long value = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        if (value == 0) {
            return "Không đồng";
        }
        if (value < 0) {
            return "Âm " + convertPositiveNumber(Math.abs(value)) + " đồng";
        }
        String text = convertPositiveNumber(value);
        if (text.isEmpty()) {
            return "Không đồng";
        }
        // Viết hoa chữ cái đầu và thêm "đồng chẵn"
        String result = Character.toUpperCase(text.charAt(0)) + text.substring(1).trim() + " đồng chẵn.";
        return result;
    }

    private static String convertPositiveNumber(long number) {
        if (number == 0) {
            return "không";
        }

        StringBuilder sb = new StringBuilder();
        int unitIndex = 0;

        while (number > 0) {
            int triplet = (int) (number % 1000);
            if (triplet != 0) {
                String tripletText = convertTriplet(triplet, number >= 1000);
                String unit = UNITS[unitIndex];
                if (!unit.isEmpty()) {
                    sb.insert(0, tripletText + " " + unit + " ");
                } else {
                    sb.insert(0, tripletText + " ");
                }
            }
            number /= 1000;
            unitIndex++;
        }

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String convertTriplet(int number, boolean showLeadingZeros) {
        int hundreds = number / 100;
        int remainder = number % 100;
        int tens = remainder / 10;
        int units = remainder % 10;

        StringBuilder sb = new StringBuilder();

        if (hundreds > 0 || showLeadingZeros) {
            sb.append(DIGITS[hundreds]).append(" trăm ");
        }

        if (tens > 1) {
            sb.append(DIGITS[tens]).append(" mươi ");
            if (units == 1) {
                sb.append("mốt ");
            } else if (units == 5) {
                sb.append("lăm ");
            } else if (units > 0) {
                sb.append(DIGITS[units]).append(" ");
            }
        } else if (tens == 1) {
            sb.append("mười ");
            if (units == 5) {
                sb.append("lăm ");
            } else if (units > 0) {
                sb.append(DIGITS[units]).append(" ");
            }
        } else {
            // tens == 0
            if (hundreds > 0 && units > 0) {
                sb.append("lẻ ");
            }
            if (units > 0) {
                sb.append(DIGITS[units]).append(" ");
            }
        }

        return sb.toString().trim();
    }
}
