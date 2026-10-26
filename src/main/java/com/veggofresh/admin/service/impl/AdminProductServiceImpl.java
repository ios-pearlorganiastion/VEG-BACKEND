package com.veggofresh.admin.service.impl;

import com.veggofresh.admin.dto.request.ProductCreateRequestDto;
import com.veggofresh.admin.dto.request.ProductRequestDto;
import com.veggofresh.admin.dto.response.ProductImageResponseDto;
import com.veggofresh.admin.dto.response.ProductResponseDto;
import com.veggofresh.admin.entity.CatalogCategory;
import com.veggofresh.admin.entity.CatalogProduct;
import com.veggofresh.admin.entity.CatalogProductImage;
import com.veggofresh.admin.entity.CatalogSubcategory;
import com.veggofresh.admin.repository.CatalogCategoryRepository;
import com.veggofresh.admin.repository.CatalogProductImageRepository;
import com.veggofresh.admin.repository.CatalogProductRepository;
import com.veggofresh.admin.repository.CatalogSubcategoryRepository;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminProductServiceImpl implements AdminProductService {

    private final CatalogProductRepository productRepository;
    private final CatalogCategoryRepository categoryRepository;
    private final CatalogSubcategoryRepository subcategoryRepository;
    private final CatalogProductImageRepository productImageRepository;
    private final CloudinaryService cloudinaryService;

    @Override
    public ProductResponseDto createProduct(ProductCreateRequestDto request) {
        CatalogCategory category = getCategory(request.getCategoryId());
        CatalogSubcategory subcategory = getSubcategory(request.getSubcategoryId());
        validateSubcategoryBelongsToCategory(subcategory, category);

        if (productRepository.existsByNameIgnoreCaseAndSubcategoryId(request.getName(), subcategory.getId())) {
            throw new BusinessException("CATALOG_PRODUCT_DUPLICATE",
                    "A product with this name already exists in this subcategory", HttpStatus.CONFLICT);
        }

        List<MultipartFile> images = request.getImages();
        if (images == null || images.isEmpty() || images.stream().allMatch(MultipartFile::isEmpty)) {
            throw new BusinessException("CATALOG_PRODUCT_IMAGE_REQUIRED",
                    "At least one product image is required", HttpStatus.BAD_REQUEST);
        }

        CatalogProduct product = new CatalogProduct();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setSubcategory(subcategory);
        product.setPrice(request.getPrice());
        validateOriginalPrice(request.getPrice(), request.getOriginalPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        BigDecimal vendorPrice = request.getVendorPrice() != null ? request.getVendorPrice() : request.getPrice();
        validateVendorPrice(request.getPrice(), vendorPrice);
        product.setVendorPrice(vendorPrice);
        product.setUnit(request.getUnit());
        product.setActive(true);
        product = productRepository.save(product);

        int sortOrder = 0;
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) continue;
            saveNewImage(product, image, sortOrder++);
        }

        return toDto(product);
    }

    @Override
    public ProductResponseDto updateProduct(UUID id, ProductRequestDto request) {
        CatalogProduct product = getEntity(id);
        CatalogCategory category = getCategory(request.getCategoryId());
        CatalogSubcategory subcategory = getSubcategory(request.getSubcategoryId());
        validateSubcategoryBelongsToCategory(subcategory, category);

        boolean nameOrSubcategoryChanged = !product.getName().equalsIgnoreCase(request.getName())
                || !product.getSubcategory().getId().equals(subcategory.getId());
        if (nameOrSubcategoryChanged
                && productRepository.existsByNameIgnoreCaseAndSubcategoryId(request.getName(), subcategory.getId())) {
            throw new BusinessException("CATALOG_PRODUCT_DUPLICATE",
                    "A product with this name already exists in this subcategory", HttpStatus.CONFLICT);
        }

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setSubcategory(subcategory);
        product.setPrice(request.getPrice());
        validateOriginalPrice(request.getPrice(), request.getOriginalPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        BigDecimal vendorPrice = request.getVendorPrice() != null ? request.getVendorPrice() : product.getVendorPrice();
        validateVendorPrice(request.getPrice(), vendorPrice);
        product.setVendorPrice(vendorPrice);
        product.setUnit(request.getUnit());
        return toDto(productRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDto getProductById(UUID id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductResponseDto> findProductById(UUID id) {
        return productRepository.findById(id).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> getVendorPrices(Collection<UUID> productIds) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return result;
        }
        for (CatalogProduct p : productRepository.findAllById(productIds)) {
            result.put(p.getId(), p.getVendorPrice() != null ? p.getVendorPrice() : p.getPrice());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDto> searchProducts(String search, UUID categoryId, UUID subcategoryId, Pageable pageable) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;
        return productRepository.search(normalizedSearch, categoryId, subcategoryId, pageable)
                .map(this::toDto);
    }

    @Override
    public ProductResponseDto setActive(UUID id, boolean active) {
        CatalogProduct product = getEntity(id);
        product.setActive(active);
        return toDto(productRepository.save(product));
    }

    // ── Product image management ────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ProductImageResponseDto> getImages(UUID productId) {
        getEntity(productId); // 404 if the product itself doesn't exist
        return productImageRepository.findByProductIdOrderBySortOrderAsc(productId)
                .stream().map(this::toImageDto).collect(Collectors.toList());
    }

    @Override
    public List<ProductImageResponseDto> addImages(UUID productId, List<MultipartFile> images) {
        CatalogProduct product = getEntity(productId);

        if (images == null || images.isEmpty() || images.stream().allMatch(MultipartFile::isEmpty)) {
            throw new BusinessException("CATALOG_PRODUCT_IMAGE_REQUIRED",
                    "At least one image file is required", HttpStatus.BAD_REQUEST);
        }

        int nextSortOrder = (int) productImageRepository.countByProductId(productId);
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) continue;
            saveNewImage(product, image, nextSortOrder++);
        }

        return getImages(productId);
    }

    @Override
    public List<ProductImageResponseDto> deleteImage(UUID productId, UUID imageId) {
        getEntity(productId); // 404 if the product itself doesn't exist

        long currentCount = productImageRepository.countByProductId(productId);
        if (currentCount <= 1) {
            throw new BusinessException("CATALOG_PRODUCT_LAST_IMAGE",
                    "Cannot delete the last remaining image -- every product must have at least one image",
                    HttpStatus.BAD_REQUEST);
        }

        CatalogProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new BusinessException("CATALOG_PRODUCT_IMAGE_NOT_FOUND",
                        "Image not found on this product", HttpStatus.NOT_FOUND));

        String publicId = image.getPublicId();
        productImageRepository.delete(image);
        cloudinaryService.deleteQuietly(publicId);

        return getImages(productId);
    }

    @Override
    public List<ProductImageResponseDto> reorderImages(UUID productId, List<UUID> imageIds) {
        getEntity(productId); // 404 if the product itself doesn't exist

        List<CatalogProductImage> current = productImageRepository.findByProductIdOrderBySortOrderAsc(productId);

        if (imageIds == null || imageIds.size() != current.size()
                || !imageIds.stream().allMatch(id -> current.stream().anyMatch(img -> img.getId().equals(id)))) {
            throw new BusinessException("CATALOG_PRODUCT_IMAGE_REORDER_MISMATCH",
                    "imageIds must contain exactly every image currently on this product, no more and no fewer",
                    HttpStatus.BAD_REQUEST);
        }

        for (int i = 0; i < imageIds.size(); i++) {
            UUID id = imageIds.get(i);
            CatalogProductImage image = current.stream()
                    .filter(img -> img.getId().equals(id))
                    .findFirst()
                    .orElseThrow(); // unreachable given the validation above
            image.setSortOrder(i);
        }
        productImageRepository.saveAll(current);

        return getImages(productId);
    }

    // -------------------------------------------------------------------------

    private void saveNewImage(CatalogProduct product, MultipartFile file, int sortOrder) {
        CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                file, "veggofresh/catalog/products/" + product.getId());
        CatalogProductImage image = new CatalogProductImage();
        image.setProduct(product);
        image.setImageUrl(upload.url());
        image.setPublicId(upload.publicId());
        image.setSortOrder(sortOrder);
        productImageRepository.save(image);
    }

    private void validateSubcategoryBelongsToCategory(CatalogSubcategory subcategory, CatalogCategory category) {
        if (!subcategory.getCategory().getId().equals(category.getId())) {
            throw new BusinessException("CATALOG_SUBCATEGORY_CATEGORY_MISMATCH",
                    "Subcategory does not belong to the given category", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Confirmed rule: reject an originalPrice that isn't strictly greater than
     * price -- an equal or lower "was" price would show a nonsensical zero or
     * negative discount badge on the browse screens.
     */
    private void validateOriginalPrice(BigDecimal price, BigDecimal originalPrice) {
        if (originalPrice != null && originalPrice.compareTo(price) <= 0) {
            throw new BusinessException("CATALOG_PRODUCT_INVALID_ORIGINAL_PRICE",
                    "originalPrice must be greater than price", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The vendor can never be paid more than the customer pays: a vendor price above the price would make the
     * platform lose money on every sale. If you lower price you must lower vendorPrice too.
     */
    private void validateVendorPrice(BigDecimal price, BigDecimal vendorPrice) {
        if (vendorPrice == null || vendorPrice.signum() <= 0) {
            throw new BusinessException("CATALOG_PRODUCT_INVALID_VENDOR_PRICE",
                    "vendorPrice must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (vendorPrice.compareTo(price) > 0) {
            throw new BusinessException("CATALOG_PRODUCT_INVALID_VENDOR_PRICE",
                    "vendorPrice must not be greater than price. If you are lowering the price, "
                            + "also lower vendorPrice.", HttpStatus.BAD_REQUEST);
        }
    }

    private CatalogProduct getEntity(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CATALOG_PRODUCT_NOT_FOUND",
                        "Product not found", HttpStatus.NOT_FOUND));
    }

    private CatalogCategory getCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException("CATALOG_CATEGORY_NOT_FOUND",
                        "Category not found", HttpStatus.NOT_FOUND));
    }

    private CatalogSubcategory getSubcategory(UUID subcategoryId) {
        return subcategoryRepository.findById(subcategoryId)
                .orElseThrow(() -> new BusinessException("CATALOG_SUBCATEGORY_NOT_FOUND",
                        "Subcategory not found", HttpStatus.NOT_FOUND));
    }

    private ProductImageResponseDto toImageDto(CatalogProductImage img) {
        return ProductImageResponseDto.builder()
                .id(img.getId())
                .imageUrl(img.getImageUrl())
                .sortOrder(img.getSortOrder())
                .build();
    }

    private ProductResponseDto toDto(CatalogProduct p) {
        List<CatalogProductImage> images = productImageRepository.findByProductIdOrderBySortOrderAsc(p.getId());
        List<String> imageUrls = images.stream().map(CatalogProductImage::getImageUrl).collect(Collectors.toList());
        String coverImageUrl = imageUrls.isEmpty() ? null : imageUrls.get(0);

        return ProductResponseDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .categoryId(p.getCategory().getId())
                .categoryName(p.getCategory().getName())
                .subcategoryId(p.getSubcategory().getId())
                .subcategoryName(p.getSubcategory().getName())
                .price(p.getPrice())
                .originalPrice(p.getOriginalPrice())
                .vendorPrice(p.getVendorPrice() != null ? p.getVendorPrice() : p.getPrice())
                .margin(p.getPrice().subtract(p.getVendorPrice() != null ? p.getVendorPrice() : p.getPrice()))
                .unit(p.getUnit())
                .discountPercent(computeDiscountPercent(p.getPrice(), p.getOriginalPrice()))
                .imageUrl(coverImageUrl)
                .imageUrls(imageUrls)
                .isActive(p.isActive())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    /**
     * The single canonical place this is computed -- every downstream consumer
     * (Vendor's ProductDto/VendorListingDto, Customer's browse screens) just
     * copies this value across rather than recomputing it, so there's exactly
     * one formula in the whole system to ever change.
     */
    private Integer computeDiscountPercent(BigDecimal price, BigDecimal originalPrice) {
        if (originalPrice == null || price == null || originalPrice.compareTo(price) <= 0) {
            return null;
        }
        BigDecimal diff = originalPrice.subtract(price);
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(originalPrice, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}