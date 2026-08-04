# Hướng dẫn publish `billing_library` lên JitPack

Tài liệu dành cho người maintain repo này. `billing_library` được public qua **JitPack**
(build theo git tag) — consumer dùng coordinate `com.github.wzlibs.billing_library:billing:<tag>`.

## 1. Bump version

Sửa `billingLibraryVersion` trong `gradle.properties` cho **khớp chính xác với tên tag bỏ tiền tố `v`**
(JitPack map version = tag không có `v`: tag `v1.2.3` → version `1.2.3`; publication version phải
khớp, nếu không JitPack sẽ 404 dù build báo "ok"):

```properties
billingLibraryVersion=0.2.0
```

## 2. Tag + push

JitPack build theo git tag — tên tag chính là version consumer dùng
(`implementation("com.github.wzlibs.billing_library:billing:<tag>")`). Nên đặt tag khớp
`billingLibraryVersion`, có tiền tố `v`:

```bash
git add -A
git commit -m "release: v0.2.0"
git push origin master
git tag -a v0.2.0 -m "Mô tả ngắn thay đổi trong bản này"
git push origin v0.2.0
```

## 3. Trigger + verify build trên JitPack

JitPack chỉ build khi có request đầu tiên tới 1 tag (không tự chạy khi tag được push). Trigger
bằng cách tải trực tiếp file `.pom`:

```bash
curl -s "https://jitpack.io/com/github/wzlibs/billing_library/billing/v0.2.0/billing-v0.2.0.pom"
```

HTTP 200 kèm nội dung POM thật = build thành công. Log build đầy đủ:
`https://jitpack.io/com/github/wzlibs/billing_library/billing/v0.2.0/build.log`.

`jitpack.yml` ở root pin sẵn JDK 21 khớp AGP/Gradle đang dùng. Nếu nâng cấp AGP/Gradle cần JDK
khác, nhớ cập nhật `jitpack.yml`, nếu không JitPack build lỗi bằng JDK cũ.

## 4. Cập nhật README (tuỳ chọn)

Ghi thêm version mới + ví dụ dependency vào README nếu cần.
