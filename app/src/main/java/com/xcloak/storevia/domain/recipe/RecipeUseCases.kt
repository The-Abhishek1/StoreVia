package com.xcloak.storevia.domain.recipe

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.OwnerAccess
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.PermissionDeniedException
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.RecipeDao
import com.xcloak.storevia.core.database.entity.RecipeComponentEntity
import javax.inject.Inject

class RecipeValidationException(message: String) : Exception(message)

class SaveRecipeComponentUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val recipeDao: RecipeDao,
    private val access: Access = OwnerAccess,
) {
    suspend operator fun invoke(recipeProductId: String, ingredientProductId: String, quantityPerUnitMilli: Long) =
        db.withTransaction {
            access.require(Permission.MANAGE_PRODUCTS)
            if (recipeProductId == ingredientProductId) throw RecipeValidationException("A product cannot be its own ingredient.")
            if (quantityPerUnitMilli <= 0) throw RecipeValidationException("Ingredient quantity must be greater than zero.")
            val recipe = productDao.getById(recipeProductId)
                ?.takeIf { it.deletedAt == null && it.isActive && it.parentProductId == null }
                ?: throw RecipeValidationException("Recipe product is unavailable.")
            if (recipe.serialTracked) throw RecipeValidationException("Serial-tracked products cannot be recipe products.")
            if (productDao.hasStockHistory(recipe.id) || productDao.currentStock(recipe.id) != 0L) {
                throw RecipeValidationException("A product with existing stock history cannot become a recipe.")
            }
            val ingredient = productDao.getById(ingredientProductId)
                ?.takeIf { it.deletedAt == null && it.isActive && it.parentProductId != recipe.id }
                ?: throw RecipeValidationException("Ingredient product is unavailable.")
            if (ingredient.serialTracked) throw RecipeValidationException("Serial-tracked products cannot be recipe ingredients.")
            if (recipeDao.hasRecipe(ingredient.id)) throw RecipeValidationException("Nested recipes are not supported.")

            val existing = recipeDao.get(recipe.id, ingredient.id)
            if (existing == null) {
                recipeDao.insert(
                    RecipeComponentEntity(
                        recipeProductId = recipe.id,
                        ingredientProductId = ingredient.id,
                        quantityPerUnitMilli = quantityPerUnitMilli,
                    ),
                )
            } else {
                recipeDao.update(
                    existing.copy(
                        quantityPerUnitMilli = quantityPerUnitMilli,
                        updatedAt = System.currentTimeMillis(),
                        deletedAt = null,
                        dirty = true,
                    ),
                )
            }
        }
}

class RemoveRecipeComponentUseCase @Inject constructor(
    private val recipeDao: RecipeDao,
    private val access: Access = OwnerAccess,
) {
    suspend operator fun invoke(id: String) {
        access.require(Permission.MANAGE_PRODUCTS)
        recipeDao.softDelete(id)
    }
}
