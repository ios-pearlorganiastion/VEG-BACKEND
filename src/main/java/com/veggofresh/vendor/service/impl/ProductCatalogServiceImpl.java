package com.veggofresh.vendor.service.impl;

import com.veggofresh.admin.dto.response.CategoryResponseDto;
import com.veggofresh.admin.dto.response.ProductResponseDto;
import com.veggofresh.admin.dto.response.SubcategoryResponseDto;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.admin.service.CatalogCategoryService;
import com.veggofresh.admin.service.CatalogSubcategoryService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.CategoryDto;
import com.veggofresh.vendor.dto.CategoryTreeDto;
import com.veggofresh.vendor.dto.ProductDto;
import com.veggofresh.vendor.dto.ShopDto;
import com.veggofresh.vendor.dto.SubcategoryDto;
import com.veggofresh.vendor.dto.SubcategoryTreeDto;
import com.veggofresh.vendor.entity.KycStatus;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.entity.VendorListing;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import com.veggofresh.vendor.service.ProductCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NEW ARCHITECTURE — catalog pivot (see NOTES_VENDOR.md). Rewritten to serve
 * Admin's master catalog through this shop's VendorListing rows, instead of
 * Vendor's old, now-deleted Product/Category entities.
 *
 * ⚠️ KNOWN SCALE CAVEAT: since eligibility (isListed + shop online/approved +
 * in-radius) can't be expressed inside Admin's searchProducts SQL query (that
 * would require Admin's repository to know about Vendor's VendorListing table,
 * a cross-module join we deliberately avoid), search/related/deals overfetch
 * a large page from Admin, filter eligibility + price range in Java, then
 * paginate in-memory. Correct at small-to-medium catalog size; revisit with a
 * real DB-level join (or a materialized/denormalized eligibility view) once
 * catalog size actually warrants it. See NOTES_VENDOR.md.
 *
 * ⚠️ RADIUS PLACEHOLDER: Admin has no real configurable delivery-radius
 * setting yet. DEFAULT_RADIUS_KM below is a stand-in — same pattern as
 * VendorTestController standing in for Admin's missing KYC endpoints. Swap
 * resolveRadiusKm() for a real Admin settings lookup once that exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogServiceImpl implements ProductCatalogService {

    /** TODO: replace with a real Admin-configurable setting once it exists. */
    private static final double DEFAULT_RADIUS_KM = 10.0;

    /** Overfetch size for the Java-side filter+paginate workaround described above. */
    private static final int OVERFETCH_SIZE = 2000;

    private final AdminProductService adminProductService;
    private final CatalogCategoryService catalogCategoryService;
    private final CatalogSubcategoryService catalogSubcategoryService;
    private final VendorListingRepository vendorListingRepository;
    private final ShopRepository shopRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ShopDto> browseNearbyShops(double latitude, double longitude) {
        double radiusKm = resolveRadiusKm();
        List<Shop> shops = shopRepository.findAllByDeletedAtIsNullAndIsOnlineTrueAndKycStatus(KycStatus.APPROVED);

        return shops.stream()
                .filter(shop -> shop.getLatitude() != null && shop.getLongitude() != null)
                .map(shop -> new Object[]{shop, calculateDistanceInKm(latitude, longitude, shop.getLatitude(), shop.getLongitude())})
                .filter(pair -> (double) pair[1] <= radiusKm)
                .sorted(Comparator.comparingDouble(pair -> (double) pair[1]))
                .map(pair -> {
                    Shop shop = (Shop) pair[0];
                    double distance = (double) pair[1];
                    return ShopDto.builder()
                            .id(shop.getId())
                            .name(shop.getName())
                            .latitude(shop.getLatitude())
                            .longitude(shop.getLongitude())
                            .distanceInKm(distance)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CategoryDto> browseCategories(String search, Pageable pageable) {
        return catalogCategoryService.searchActiveCategories(search, pageable)
                .map(c -> CategoryDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .description(c.getDescription())
                        .iconUrl(c.getImageUrl())
                        .isActive(c.isActive())
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubcategoryDto> browseSubcategories(UUID categoryId, String search, Pageable pageable) {
        return catalogSubcategoryService.searchActiveSubcategories(categoryId, search, pageable)
                .map(s -> SubcategoryDto.builder()
                        .id(s.getId())
                        .categoryId(s.getCategoryId())
                        .categoryName(s.getCategoryName())
                        .name(s.getName())
                        .imageUrl(s.getImageUrl())
                        .isActive(s.isActive())
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDto> searchProducts(String query, UUID categoryId, UUID subcategoryId, Double minPrice, Double maxPrice,
                                            double latitude, double longitude, Pageable pageable) {
        Page<ProductResponseDto> candidates = adminProductService.searchProducts(
                query, categoryId, subcategoryId, PageRequest.of(0, OVERFETCH_SIZE));

        List<ProductDto> eligible = candidates.getContent().stream()
                .filter(ProductResponseDto::isActive)
                .filter(p -> withinPriceRange(p.getPrice(), minPrice, maxPrice))
                .map(p -> toProductDtoIfEligible(p, latitude, longitude))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        return paginateInMemory(eligible, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDto getProductById(UUID catalogProductId, double latitude, double longitude) {
        // Must use the Optional-returning lookup, NOT getProductById inside a
        // try/catch. AdminProductServiceImpl is @Transactional and joins the
        // CALLER's transaction, so a RuntimeException escaping it makes Spring
        // mark that shared transaction rollback-only -- and no noRollbackFor on
        // this method can un-poison it. A caller that swallows our
        // BusinessException (CartServiceImpl.safeGetProduct) would then hit
        // UnexpectedRollbackException at commit. Returning Optional lets the
        // "not found" case be expressed as data, not as a failed nested
        // transaction.
        ProductResponseDto product = adminProductService.findProductById(catalogProductId)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found"));

        if (!product.isActive()) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found or inactive");
        }

        return toProductDtoIfEligible(product, latitude, longitude)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "This product isn't available from any vendor near you right now"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDto> findEligibleProductById(UUID catalogProductId, double latitude, double longitude) {
        // Pure read, no exceptions on the "not available" path -- see the
        // interface Javadoc for why that distinction is load-bearing for every
        // caller resolving products for rows that already exist (cart items,
        // wishlist entries, order lines).
        return adminProductService.findProductById(catalogProductId)
                .filter(ProductResponseDto::isActive)
                .flatMap(product -> toProductDtoIfEligible(product, latitude, longitude));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDto> getRelatedProducts(UUID catalogProductId, double latitude, double longitude) {
        // Same reasoning as getProductById: no exception-swallowing around a
        // nested @Transactional call, or this poisons the caller's transaction.
        ProductResponseDto product = adminProductService.findProductById(catalogProductId)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found"));

        Page<ProductResponseDto> candidates = adminProductService.searchProducts(
                null, null, product.getSubcategoryId(), PageRequest.of(0, OVERFETCH_SIZE));

        return candidates.getContent().stream()
                .filter(ProductResponseDto::isActive)
                .filter(p -> !p.getId().equals(catalogProductId))
                .map(p -> toProductDtoIfEligible(p, latitude, longitude))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDto> getDailyDeals(double latitude, double longitude) {
        Page<ProductResponseDto> candidates = adminProductService.searchProducts(
                null, null, null, PageRequest.of(0, OVERFETCH_SIZE));

        return candidates.getContent().stream()
                .filter(ProductResponseDto::isActive)
                // A "deal" = Admin has set a genuine discount (discountPercent is
                // only non-null when originalPrice > price -- see AdminProductServiceImpl
                // .computeDiscountPercent()).
                .filter(p -> p.getDiscountPercent() != null)
                .map(p -> toProductDtoIfEligible(p, latitude, longitude))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDto> getAllCategories() {
        List<CategoryResponseDto> categories = catalogCategoryService.listCategories(false);
        return categories.stream()
                .map(c -> CategoryDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .description(c.getDescription())
                        .iconUrl(c.getImageUrl())
                        .isActive(c.isActive())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> getShopIdsForProduct(UUID catalogProductId, double latitude, double longitude) {
        double radiusKm = resolveRadiusKm();
        return vendorListingRepository.findByCatalogProductIdAndIsListedTrue(catalogProductId).stream()
                .map(VendorListing::getShop)
                .filter(shop -> isShopEligible(shop, latitude, longitude, radiusKm))
                .map(Shop::getId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryTreeDto getCategoryTree(UUID categoryId, double latitude, double longitude) {
        CategoryResponseDto category = catalogCategoryService.getCategoryById(categoryId);
        // Customers must never see an inactive category (same 404 as a category that does not exist).
        if (!category.isActive()) {
            throw new BusinessException("CATALOG_CATEGORY_NOT_FOUND", "Category not found", HttpStatus.NOT_FOUND);
        }
        return buildCategoryTree(category, latitude, longitude);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTreeDto> getFullCatalogTree(double latitude, double longitude) {
        return catalogCategoryService.listCategories(false).stream()
                .map(category -> buildCategoryTree(category, latitude, longitude))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private CategoryTreeDto buildCategoryTree(CategoryResponseDto category, double latitude, double longitude) {
        List<SubcategoryTreeDto> subcategoryTrees = catalogSubcategoryService.listActiveByCategory(category.getId()).stream()
                .map(subcategory -> buildSubcategoryTree(subcategory, latitude, longitude))
                .collect(Collectors.toList());

        return CategoryTreeDto.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .imageUrl(category.getImageUrl())
                .subcategories(subcategoryTrees)
                .build();
    }

    private SubcategoryTreeDto buildSubcategoryTree(SubcategoryResponseDto subcategory, double latitude, double longitude) {
        Page<ProductResponseDto> candidates = adminProductService.searchProducts(
                null, subcategory.getCategoryId(), subcategory.getId(), PageRequest.of(0, OVERFETCH_SIZE));

        List<ProductDto> products = candidates.getContent().stream()
                .filter(ProductResponseDto::isActive)
                .map(p -> toProductDtoIfEligible(p, latitude, longitude))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        return SubcategoryTreeDto.builder()
                .id(subcategory.getId())
                .name(subcategory.getName())
                .imageUrl(subcategory.getImageUrl())
                .products(products)
                .build();
    }

    private double resolveRadiusKm() {
        return DEFAULT_RADIUS_KM;
    }

    private boolean isShopEligible(Shop shop, double latitude, double longitude, double radiusKm) {
        // No explicit soft-delete check needed here -- @Where(clause = "deleted_at IS
        // NULL") on Shop already excludes deleted rows at the query/relation level.
        if (shop == null) return false;
        if (!shop.isOnline() || shop.getKycStatus() != KycStatus.APPROVED) return false;
        if (shop.getLatitude() == null || shop.getLongitude() == null) return false;
        double distance = calculateDistanceInKm(latitude, longitude, shop.getLatitude(), shop.getLongitude());
        return distance <= radiusKm;
    }

    private boolean withinPriceRange(BigDecimal price, Double minPrice, Double maxPrice) {
        if (price == null) return false;
        if (minPrice != null && price.compareTo(BigDecimal.valueOf(minPrice)) < 0) return false;
        if (maxPrice != null && price.compareTo(BigDecimal.valueOf(maxPrice)) > 0) return false;
        return true;
    }

    /**
     * Resolves eligibility for one catalog product and, if eligible, maps it
     * to the cross-module ProductDto shape, picking the nearest eligible shop
     * as the single "representative" shop for display fields (shopId/shopName)
     * -- ProductDto has singular shop fields; getShopIdsForProduct is the real
     * multi-vendor-aware method Customer's cart logic actually keys off.
     */
    private Optional<ProductDto> toProductDtoIfEligible(ProductResponseDto product, double latitude, double longitude) {
        double radiusKm = resolveRadiusKm();

        List<VendorListing> listings = vendorListingRepository.findByCatalogProductIdAndIsListedTrue(product.getId());

        Shop nearestEligible = null;
        double nearestDistance = Double.MAX_VALUE;
        for (VendorListing listing : listings) {
            Shop shop = listing.getShop();
            if (!isShopEligible(shop, latitude, longitude, radiusKm)) continue;
            double distance = calculateDistanceInKm(latitude, longitude, shop.getLatitude(), shop.getLongitude());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestEligible = shop;
            }
        }

        if (nearestEligible == null) {
            return Optional.empty();
        }

        return Optional.of(ProductDto.builder()
                .id(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .originalPrice(product.getOriginalPrice())
                .description(product.getDescription())
                .shopId(nearestEligible.getId())
                .shopName(nearestEligible.getName())
                .category(product.getCategoryName())
                .imageUrl(product.getImageUrl())
                .imageUrls(product.getImageUrls())
                .unit(product.getUnit())
                .discountPercent(product.getDiscountPercent())
                // Not sourced from CatalogProduct -- separate, still-deferred
                // merchandising decisions (see NOTES_VENDOR.md), left as-is.
                .isBestSeller(false)
                .badge(null)
                .whyItsGreat(null)
                .storageTips(null)
                .build());
    }

    private Page<ProductDto> paginateInMemory(List<ProductDto> all, Pageable pageable) {
        int start = (int) pageable.getOffset();
        if (start >= all.size()) {
            return new PageImpl<>(List.of(), pageable, all.size());
        }
        int end = Math.min(start + pageable.getPageSize(), all.size());
        return new PageImpl<>(new ArrayList<>(all.subList(start, end)), pageable, all.size());
    }

    private double calculateDistanceInKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c;
        return Math.round(distance * 10.0) / 10.0;
    }
}
