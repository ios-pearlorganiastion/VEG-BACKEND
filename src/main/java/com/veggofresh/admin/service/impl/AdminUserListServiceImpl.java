package com.veggofresh.admin.service.impl;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.veggofresh.admin.dto.AdminCustomerDto;
import com.veggofresh.admin.dto.AdminDeliveryPartnerDto;
import com.veggofresh.admin.dto.AdminVendorDto;
import com.veggofresh.admin.service.AdminUserListService;
import com.veggofresh.auth.entity.User;
import com.veggofresh.auth.repository.UserRepository;
import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.complaint.repository.ComplaintRepository;
import com.veggofresh.customer.entity.CustomerProfile;
import com.veggofresh.customer.repository.CustomerProfileRepository;
import com.veggofresh.customer.repository.OrderRepository;
import com.veggofresh.delivery.entity.DeliveryAssignmentStatus;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryAssignmentRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.entity.KycStatus;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementation of {@link AdminUserListService}.
 *
 * <p>Strategy for customer/delivery: the profile tables (customer_profiles,
 * delivery_partners) are the primary filter tables.  For vendor the shop table
 * already holds name/email/phone — no secondary lookup needed per row.
 * Auth-user data (isBlocked, isVerified, phone) is fetched in a single
 * batch IN-query per page to avoid N+1 selects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserListServiceImpl implements AdminUserListService {

    private static final int PDF_MAX_ROWS = 500;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeliveryPartnerProfileRepository deliveryPartnerProfileRepository;
    private final OrderRepository orderRepository;
    private final ComplaintRepository complaintRepository;
    private final VendorListingRepository vendorListingRepository;
    private final DeliveryAssignmentRepository deliveryAssignmentRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // VENDORS
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AdminVendorDto> getAllVendors(String search, KycStatus kycStatus, Boolean isOnline, Pageable pageable) {
        String cleanSearch = clean(search);
        Page<Shop> page = shopRepository.findWithFilters(cleanSearch, kycStatus, isOnline, pageable);
        List<UUID> userIds = page.stream().map(Shop::getOwnerUserId).collect(Collectors.toList());
        List<UUID> shopIds = page.stream().map(Shop::getId).collect(Collectors.toList());
        Map<UUID, User> userMap = batchLoadUsers(userIds);
        Map<UUID, Long> orderCountMap = batchCountOrdersByShop(shopIds);
        Map<UUID, Long> listingCountMap = batchCountListingsByShop(shopIds);
        Map<UUID, Long> complaintCountByUser = batchCountComplaintsByUser(userIds);
        return page.map(shop -> toVendorDto(shop, userMap.get(shop.getOwnerUserId()),
                orderCountMap.getOrDefault(shop.getId(), 0L),
                listingCountMap.getOrDefault(shop.getId(), 0L),
                complaintCountByUser.getOrDefault(shop.getOwnerUserId(), 0L)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminVendorDto> getVendorsForExport(String search, KycStatus kycStatus, Boolean isOnline, int limit) {
        String cleanSearch = clean(search);
        Pageable exportPage = PageRequest.of(0, Math.min(limit, PDF_MAX_ROWS));
        List<Shop> shops = shopRepository.findWithFiltersNoPage(cleanSearch, kycStatus, isOnline, exportPage);
        List<UUID> userIds = shops.stream().map(Shop::getOwnerUserId).collect(Collectors.toList());
        List<UUID> shopIds = shops.stream().map(Shop::getId).collect(Collectors.toList());
        Map<UUID, User> userMap = batchLoadUsers(userIds);
        Map<UUID, Long> orderCountMap = batchCountOrdersByShop(shopIds);
        Map<UUID, Long> listingCountMap = batchCountListingsByShop(shopIds);
        Map<UUID, Long> complaintCountByUser = batchCountComplaintsByUser(userIds);
        return shops.stream()
                .map(shop -> toVendorDto(shop, userMap.get(shop.getOwnerUserId()),
                        orderCountMap.getOrDefault(shop.getId(), 0L),
                        listingCountMap.getOrDefault(shop.getId(), 0L),
                        complaintCountByUser.getOrDefault(shop.getOwnerUserId(), 0L)))
                .collect(Collectors.toList());
    }

    @Override
    public byte[] generateVendorsPdf(List<AdminVendorDto> vendors) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4.rotate());

            PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            // Title
            doc.add(new Paragraph("VegGoFresh — All Vendors Report")
                    .setFont(bold).setFontSize(16)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(4));
            doc.add(new Paragraph("Generated: " + DATE_FMT.format(java.time.Instant.now()) + " IST  |  Total: " + vendors.size())
                    .setFont(regular).setFontSize(9)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(12));

            // Table
            float[] colWidths = {2.5f, 2.5f, 2.5f, 2f, 2f, 1.5f, 1.2f, 1.2f, 1.2f, 1.2f, 1.8f};
            Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();

            // Header row
            String[] headers = {"Shop Name", "Owner Name", "Email", "Phone", "City", "KYC Status", "Online", "Orders", "Listings", "Complaints", "Registered"};
            DeviceRgb headerBg = new DeviceRgb(34, 139, 34); // green
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(ColorConstants.WHITE))
                        .setBackgroundColor(headerBg).setPadding(4));
            }

            // Data rows
            boolean alternate = false;
            DeviceRgb altBg = new DeviceRgb(240, 248, 240);
            for (AdminVendorDto v : vendors) {
                DeviceRgb bg = alternate ? altBg : null;
                addCell(table, v.getShopName(), regular, 8, bg);
                addCell(table, v.getOwnerName(), regular, 8, bg);
                addCell(table, v.getEmail(), regular, 7, bg);
                addCell(table, v.getPhone(), regular, 8, bg);
                addCell(table, v.getCity(), regular, 8, bg);
                addCell(table, v.getKycStatus() != null ? v.getKycStatus().name() : "—", regular, 8, bg);
                addCell(table, v.isOnline() ? "Yes" : "No", regular, 8, bg);
                addCell(table, String.valueOf(v.getTotalOrdersReceived()), regular, 8, bg);
                addCell(table, String.valueOf(v.getTotalListings()), regular, 8, bg);
                addCell(table, String.valueOf(v.getTotalComplaints()), regular, 8, bg);
                addCell(table, v.getRegisteredAt() != null ? DATE_FMT.format(v.getRegisteredAt()) : "—", regular, 7, bg);
                alternate = !alternate;
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate vendors PDF", e);
            throw new BusinessException("PDF_ERROR", "Failed to generate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOMERS
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCustomerDto> getAllCustomers(String search, Boolean isBlocked, Boolean isVerified, Pageable pageable) {
        String cleanSearch = clean(search);

        if (isBlocked != null || isVerified != null) {
            Page<User> userPage = userRepository.findByRoleWithFilters(
                    UserRole.CUSTOMER, cleanSearch, isBlocked, isVerified, pageable);
            List<UUID> userIds = userPage.stream().map(User::getId).collect(Collectors.toList());
            Map<UUID, CustomerProfile> profileMap = batchLoadCustomerProfiles(userIds);
            Map<UUID, Long> orderCounts = batchCountOrdersByUser(userIds);
            Map<UUID, Long> complaintCounts = batchCountComplaintsByUser(userIds);
            return userPage.map(user -> toCustomerDto(user, profileMap.get(user.getId()),
                    orderCounts.getOrDefault(user.getId(), 0L),
                    complaintCounts.getOrDefault(user.getId(), 0L)));
        } else {
            Page<CustomerProfile> profilePage = customerProfileRepository.findWithFilters(cleanSearch, pageable);
            List<UUID> userIds = profilePage.stream().map(CustomerProfile::getUserId).collect(Collectors.toList());
            Map<UUID, User> userMap = batchLoadUsers(userIds);
            Map<UUID, Long> orderCounts = batchCountOrdersByUser(userIds);
            Map<UUID, Long> complaintCounts = batchCountComplaintsByUser(userIds);
            return profilePage.map(cp -> toCustomerDto(userMap.get(cp.getUserId()), cp,
                    orderCounts.getOrDefault(cp.getUserId(), 0L),
                    complaintCounts.getOrDefault(cp.getUserId(), 0L)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminCustomerDto> getCustomersForExport(String search, Boolean isBlocked, Boolean isVerified, int limit) {
        String cleanSearch = clean(search);
        Pageable exportPage = PageRequest.of(0, Math.min(limit, PDF_MAX_ROWS));

        if (isBlocked != null || isVerified != null) {
            List<User> users = userRepository.findByRoleWithFiltersNoPage(
                    UserRole.CUSTOMER, cleanSearch, isBlocked, isVerified, exportPage);
            List<UUID> userIds = users.stream().map(User::getId).collect(Collectors.toList());
            Map<UUID, CustomerProfile> profileMap = batchLoadCustomerProfiles(userIds);
            Map<UUID, Long> orderCounts = batchCountOrdersByUser(userIds);
            Map<UUID, Long> complaintCounts = batchCountComplaintsByUser(userIds);
            return users.stream().map(u -> toCustomerDto(u, profileMap.get(u.getId()),
                    orderCounts.getOrDefault(u.getId(), 0L),
                    complaintCounts.getOrDefault(u.getId(), 0L))).collect(Collectors.toList());
        } else {
            List<CustomerProfile> profiles = customerProfileRepository.findWithFiltersNoPage(cleanSearch, exportPage);
            List<UUID> userIds = profiles.stream().map(CustomerProfile::getUserId).collect(Collectors.toList());
            Map<UUID, User> userMap = batchLoadUsers(userIds);
            Map<UUID, Long> orderCounts = batchCountOrdersByUser(userIds);
            Map<UUID, Long> complaintCounts = batchCountComplaintsByUser(userIds);
            return profiles.stream().map(cp -> toCustomerDto(userMap.get(cp.getUserId()), cp,
                    orderCounts.getOrDefault(cp.getUserId(), 0L),
                    complaintCounts.getOrDefault(cp.getUserId(), 0L))).collect(Collectors.toList());
        }
    }

    @Override
    public byte[] generateCustomersPdf(List<AdminCustomerDto> customers) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4.rotate());

            PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            doc.add(new Paragraph("VegGoFresh — All Customers Report")
                    .setFont(bold).setFontSize(16)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(4));
            doc.add(new Paragraph("Generated: " + DATE_FMT.format(java.time.Instant.now()) + " IST  |  Total: " + customers.size())
                    .setFont(regular).setFontSize(9)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(12));

            float[] colWidths = {2.5f, 2.5f, 1.8f, 1.5f, 1.2f, 1.2f, 1.2f, 1.2f, 2f};
            Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();

            String[] headers = {"Full Name", "Email", "Phone", "Status", "Verified", "Blocked", "Orders", "Complaints", "Registered"};
            DeviceRgb headerBg = new DeviceRgb(0, 120, 212);
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(ColorConstants.WHITE))
                        .setBackgroundColor(headerBg).setPadding(4));
            }

            boolean alternate = false;
            DeviceRgb altBg = new DeviceRgb(235, 245, 255);
            for (AdminCustomerDto c : customers) {
                DeviceRgb bg = alternate ? altBg : null;
                addCell(table, c.getFullName(), regular, 8, bg);
                addCell(table, c.getEmail(), regular, 7, bg);
                addCell(table, c.getPhone(), regular, 8, bg);
                addCell(table, c.getAccountState(), regular, 8, bg);
                addCell(table, c.isVerified() ? "Yes" : "No", regular, 8, bg);
                addCell(table, c.isBlocked() ? "Yes" : "No", regular, 8, bg);
                addCell(table, String.valueOf(c.getTotalOrders()), regular, 8, bg);
                addCell(table, String.valueOf(c.getTotalComplaints()), regular, 8, bg);
                addCell(table, c.getRegisteredAt() != null ? DATE_FMT.format(c.getRegisteredAt()) : "—", regular, 7, bg);
                alternate = !alternate;
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate customers PDF", e);
            throw new BusinessException("PDF_ERROR", "Failed to generate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELIVERY PARTNERS
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AdminDeliveryPartnerDto> getAllDeliveryPartners(String search, DeliveryKycStatus kycStatus, Boolean isOnline, Pageable pageable) {
        String cleanSearch = clean(search);
        Page<DeliveryPartnerProfile> page = deliveryPartnerProfileRepository.findWithFilters(cleanSearch, kycStatus, isOnline, pageable);
        List<UUID> userIds = page.stream().map(DeliveryPartnerProfile::getUserId).collect(Collectors.toList());
        Map<UUID, User> userMap = batchLoadUsers(userIds);
        Map<UUID, Long> deliveryCountMap = batchCountDeliveriesByUser(userIds);
        Map<UUID, Long> complaintCountMap = batchCountComplaintsByUser(userIds);
        return page.map(dp -> toDeliveryDto(dp, userMap.get(dp.getUserId()),
                deliveryCountMap.getOrDefault(dp.getUserId(), 0L),
                complaintCountMap.getOrDefault(dp.getUserId(), 0L)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminDeliveryPartnerDto> getDeliveryPartnersForExport(String search, DeliveryKycStatus kycStatus, Boolean isOnline, int limit) {
        String cleanSearch = clean(search);
        Pageable exportPage = PageRequest.of(0, Math.min(limit, PDF_MAX_ROWS));
        List<DeliveryPartnerProfile> list = deliveryPartnerProfileRepository.findWithFiltersNoPage(cleanSearch, kycStatus, isOnline, exportPage);
        List<UUID> userIds = list.stream().map(DeliveryPartnerProfile::getUserId).collect(Collectors.toList());
        Map<UUID, User> userMap = batchLoadUsers(userIds);
        Map<UUID, Long> deliveryCountMap = batchCountDeliveriesByUser(userIds);
        Map<UUID, Long> complaintCountMap = batchCountComplaintsByUser(userIds);
        return list.stream().map(dp -> toDeliveryDto(dp, userMap.get(dp.getUserId()),
                deliveryCountMap.getOrDefault(dp.getUserId(), 0L),
                complaintCountMap.getOrDefault(dp.getUserId(), 0L))).collect(Collectors.toList());
    }

    @Override
    public byte[] generateDeliveryPartnersPdf(List<AdminDeliveryPartnerDto> partners) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4.rotate());

            PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            doc.add(new Paragraph("VegGoFresh — All Delivery Partners Report")
                    .setFont(bold).setFontSize(16)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(4));
            doc.add(new Paragraph("Generated: " + DATE_FMT.format(java.time.Instant.now()) + " IST  |  Total: " + partners.size())
                    .setFont(regular).setFontSize(9)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(12));

            float[] colWidths = {2.5f, 2.5f, 2f, 1.8f, 1.5f, 1.5f, 1.2f, 1.2f, 1.2f, 1.8f};
            Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();

            String[] headers = {"Full Name", "Email", "Phone", "City", "Vehicle", "KYC Status", "Online", "Deliveries", "Complaints", "Registered"};
            DeviceRgb headerBg = new DeviceRgb(150, 75, 0); // brown
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(ColorConstants.WHITE))
                        .setBackgroundColor(headerBg).setPadding(4));
            }

            boolean alternate = false;
            DeviceRgb altBg = new DeviceRgb(250, 240, 230);
            for (AdminDeliveryPartnerDto d : partners) {
                DeviceRgb bg = alternate ? altBg : null;
                addCell(table, d.getFullName(), regular, 8, bg);
                addCell(table, d.getEmail(), regular, 7, bg);
                addCell(table, d.getPhone(), regular, 8, bg);
                addCell(table, d.getCityOfOperation(), regular, 8, bg);
                addCell(table, d.getVehicleType(), regular, 8, bg);
                addCell(table, d.getKycStatus() != null ? d.getKycStatus().name() : "—", regular, 8, bg);
                addCell(table, d.isOnline() ? "Yes" : "No", regular, 8, bg);
                addCell(table, String.valueOf(d.getTotalDeliveries()), regular, 8, bg);
                addCell(table, String.valueOf(d.getTotalComplaints()), regular, 8, bg);
                addCell(table, d.getRegisteredAt() != null ? DATE_FMT.format(d.getRegisteredAt()) : "—", regular, 7, bg);
                alternate = !alternate;
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate delivery partners PDF", e);
            throw new BusinessException("PDF_ERROR", "Failed to generate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String clean(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : null;
    }

    /**
     * Batch load Users by their IDs — avoids N+1 select per page row.
     */
    private Map<UUID, User> batchLoadUsers(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /**
     * Batch load CustomerProfiles by user IDs.
     */
    private Map<UUID, CustomerProfile> batchLoadCustomerProfiles(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return customerProfileRepository.findAll().stream()
                .filter(cp -> userIds.contains(cp.getUserId()))
                .collect(Collectors.toMap(CustomerProfile::getUserId, Function.identity()));
    }

    /**
     * Batch count orders accepted by vendor shops.
     */
    private Map<UUID, Long> batchCountOrdersByShop(List<UUID> shopIds) {
        if (shopIds.isEmpty()) return Map.of();
        List<Object[]> rows = orderRepository.countGroupedByAcceptedShopIdIn(shopIds);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    /**
     * Batch count active listings per vendor shop.
     */
    private Map<UUID, Long> batchCountListingsByShop(List<UUID> shopIds) {
        if (shopIds.isEmpty()) return Map.of();
        List<Object[]> rows = vendorListingRepository.countGroupedByShopIdIn(shopIds);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    /**
     * Batch count orders placed by customer user IDs.
     */
    private Map<UUID, Long> batchCountOrdersByUser(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<Object[]> rows = orderRepository.countGroupedByUserIdIn(userIds);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    /**
     * Batch count complaints filed by user IDs.
     */
    private Map<UUID, Long> batchCountComplaintsByUser(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<Object[]> rows = complaintRepository.countGroupedByUserIdIn(userIds);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    /**
     * Batch count completed deliveries by delivery partner user IDs.
     */
    private Map<UUID, Long> batchCountDeliveriesByUser(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<Object[]> rows = deliveryAssignmentRepository.countGroupedByPartnerUserIdInAndStatus(
                userIds, DeliveryAssignmentStatus.DELIVERED);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    private AdminVendorDto toVendorDto(Shop shop, User user, long orders, long listings, long complaints) {
        return AdminVendorDto.builder()
                .shopId(shop.getId())
                .userId(shop.getOwnerUserId())
                .shopName(shop.getName())
                .ownerName(shop.getFullName())
                .email(shop.getEmail() != null ? shop.getEmail() : (user != null ? user.getEmail() : null))
                .phone(shop.getBusinessPhone() != null ? shop.getBusinessPhone() : (user != null ? user.getPhone() : null))
                .city(shop.getCity())
                .state(shop.getState())
                .businessType(shop.getBusinessType())
                .kycStatus(shop.getKycStatus())
                .isOnline(shop.isOnline())
                .isBlocked(user != null && user.isBlocked())
                .isVerified(user != null && user.isVerified())
                .storeImageUrl(shop.getStoreImageUrl())
                .registeredAt(shop.getCreatedAt())
                .updatedAt(shop.getUpdatedAt())
                .applicationSubmittedAt(shop.getApplicationSubmittedAt())
                .totalOrdersReceived(orders)
                .totalListings(listings)
                .totalComplaints(complaints)
                .build();
    }

    private AdminCustomerDto toCustomerDto(User user, CustomerProfile profile, long orders, long complaints) {
        String name = (profile != null && profile.getFullName() != null) ? profile.getFullName() : null;
        String email = (profile != null && profile.getEmail() != null) ? profile.getEmail()
                : (user != null ? user.getEmail() : null);
        String avatarUrl = profile != null ? profile.getAvatarUrl() : null;

        boolean blocked = user != null && user.isBlocked();
        boolean verified = user != null && user.isVerified();
        String state = blocked ? "Blocked" : (!verified ? "Unverified" : "Active");

        return AdminCustomerDto.builder()
                .profileId(profile != null ? profile.getId() : null)
                .userId(user != null ? user.getId() : null)
                .fullName(name)
                .email(email)
                .phone(user != null ? user.getPhone() : null)
                .avatarUrl(avatarUrl)
                .isBlocked(blocked)
                .isVerified(verified)
                .accountState(state)
                .registeredAt(user != null ? user.getCreatedAt() : null)
                .updatedAt(profile != null ? profile.getUpdatedAt() : (user != null ? user.getUpdatedAt() : null))
                .totalOrders(orders)
                .totalComplaints(complaints)
                .build();
    }

    private AdminDeliveryPartnerDto toDeliveryDto(DeliveryPartnerProfile dp, User user, long deliveries, long complaints) {
        String email = (dp.getEmail() != null) ? dp.getEmail() : (user != null ? user.getEmail() : null);
        boolean blocked = user != null && user.isBlocked();
        boolean verified = user != null && user.isVerified();

        return AdminDeliveryPartnerDto.builder()
                .profileId(dp.getId())
                .userId(dp.getUserId())
                .fullName(dp.getFullName())
                .email(email)
                .phone(user != null ? user.getPhone() : null)
                .avatarUrl(dp.getAvatarUrl())
                .vehicleType(dp.getVehicleType())
                .vehicleModel(dp.getVehicleModel())
                .plateNumber(dp.getPlateNumber())
                .cityOfOperation(dp.getCityOfOperation())
                .kycStatus(dp.getKycStatus())
                .isOnline(dp.isOnline())
                .isBlocked(blocked)
                .isVerified(verified)
                .verificationStep(dp.getVerificationStep())
                .registeredAt(dp.getCreatedAt())
                .updatedAt(dp.getUpdatedAt())
                .totalDeliveries(deliveries)
                .totalComplaints(complaints)
                .build();
    }

    /** Helper to add a styled cell to the PDF table. */
    private void addCell(Table table, String text, PdfFont font, float size, DeviceRgb bg) {
        Cell cell = new Cell().add(new Paragraph(text != null ? text : "—").setFont(font).setFontSize(size)).setPadding(3);
        if (bg != null) cell.setBackgroundColor(bg);
        table.addCell(cell);
    }
}
