package com.jinbon.domain.video.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** 카메라의 회전 메타데이터를 적용해 재인코딩 사본과 같은 표시 방향으로 비교한다. */
final class VideoFrameOrientation {
    private VideoFrameOrientation() {}

    static BufferedImage toDisplay(BufferedImage image, double rotationDegrees) {
        if (!Double.isFinite(rotationDegrees) || rotationDegrees % 360 == 0) return image;
        // FFmpeg는 반시계 방향 각도, Java2D 화면 좌표는 시계 방향 각도를 사용한다.
        double radians = Math.toRadians(-rotationDegrees);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int width = (int) Math.round(image.getWidth() * cos + image.getHeight() * sin);
        int height = (int) Math.round(image.getHeight() * cos + image.getWidth() * sin);
        BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.translate(width / 2.0, height / 2.0);
            graphics.rotate(radians);
            graphics.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
    }
}
